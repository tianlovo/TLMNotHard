package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUseShieldTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedRideController;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatPlan.Result;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatFeedback;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidEquipmentTransfer;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 只执行当前首段；实际伤害、消耗、举盾和导航仍通过各自已有权威入口 */
public final class MaidUnifiedCombatActionRunner {

    private static final float APPROACH_SPEED = .6F;
    private static final double BOW_STAND_DISTANCE = 8, HEAD_SWEEP = 1.3;
    // 无跳跃药水的原生飞行约12tick；16tick无阻位移比0.91空气阻力的整段尾和还保守
    private static final int JUMP_DRIFT_TICKS = 16;
    private static final double JUMP_SWEEP_EPSILON = .0001;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final TaskAttack targets = new TaskAttack();
    private final MaidUseShieldTask shield = new MaidUseShieldTask();
    private boolean jumpRequested, jumpPreparing, airborne;
    private long jumpAt;
    private java.util.UUID jumpTarget;
    private ItemStack jumpWeapon;
    private long jumpEpoch, routeApprovedAt;
    private double jumpExpectedDamage, approvedFootY;
    private Point approvedPoint;
    private final CombatSpace.Diagnostic jumpHeadCheck = new CombatSpace.Diagnostic();
    private final CombatSpace.Diagnostic jumpSupportCheck = new CombatSpace.Diagnostic();
    private String jumpDiagnostic = "not_checked";
    private boolean headChecked, supportChecked;
    private double sweepMinX, sweepMinY, sweepMinZ, sweepMaxX, sweepMaxY, sweepMaxZ;
    private Result jumpMeleeResult;

    public MaidUnifiedCombatActionRunner(EntityMaid maid, ModernFollowCoreActor actor) {
        this.maid = maid;
        this.actor = actor;
    }

    public Result apply(CombatPlan plan) {
        Result result = applyCurrent(plan);
        if (result == Result.STALE || result == Result.REJECTED
            || result == Result.HELD
            || result == Result.WAIT_BUDGET) {
            if (actor.isCurrent()) {
                actor.eraseWalkTarget();
                if (result != Result.WAIT_BUDGET) stopUse();
            }
            if (result != Result.WAIT_BUDGET) jumpRequested = jumpPreparing = false;
        }
        return result;
    }

    private Result applyCurrent(CombatPlan plan) {
        if (!authorized() || plan == null) return Result.REJECTED;
        if (maid.getCombatHitReceipt()
            .blocksTransfer()) return Result.HELD;
        long now = maid.worldObj.getTotalWorldTime();
        if (plan.entityEpoch != actor.unifiedCombatEpoch() || plan.observationEpoch != actor.combatObservation()
            .epoch() || now < plan.observedAt || now > plan.expiresAt) return Result.STALE;
        // 计划可跨tick继续，提交成本必须扣当前世界账本，不能使用计划里的旧quota
        var quota = CombatWorldBudget.heartbeat(maid.worldObj, maid.getUniqueID(), now, false);
        if (!quota.spend(Work.FINAL_CHECK)) return Result.WAIT_BUDGET;
        if (plan.targetId == null) {
            stopUse();
            actor.eraseWalkTarget();
            MaidCombatFeedback.unsupported(maid);
            return Result.REJECTED;
        }
        var resolved = maid.worldObj.getEntityByID(plan.targetEntityId);
        if (!(resolved instanceof EntityLivingBase target) || !plan.targetId.equals(target.getUniqueID())
            || !targetIdentity(target)) return Result.STALE;
        if (maid.getModernCombatMemory()
            .target() != target) {
            if (!actor.setAttackTarget(actor.combatTargetView(target)) || !authorized()
                || maid.getModernCombatMemory()
                    .target() != target)
                return Result.STALE;
        }
        if (plan.macro == Macro.WAIT) {
            actor.stopOwnedRangedUse();
            actor.eraseWalkTarget();
            return Result.RUNNING;
        }
        int slot = plan.weaponSlot < 0 ? MaidInventory.MAIN_HAND : plan.weaponSlot;
        if (slot < 0 || slot >= MaidInventory.BACKPACK_SIZE && slot != MaidInventory.MAIN_HAND) return Result.REJECTED;
        ItemStack expected = maid.getMaidInventory()
            .getStackInSlot(slot);
        if (!CombatObservation.fingerprint(expected)
            .equals(plan.weaponFingerprint)) return Result.STALE;
        if (plan.weaponSlot >= 0) {
            jumpRequested = jumpPreparing = false;
            stopUse();
            if (!authorized()) return Result.STALE;
            var before = MaidEquipmentTransfer.capture(maid);
            if (before == null) return Result.REJECTED;
            var result = MaidEquipmentTransfer.exchange(maid, before, slot, this::authorized);
            if (result.changed()) {
                maid.getCombatTaskState()
                    .consumePreference();
                MaidCombatFeedback.supported(maid);
                return Result.WAIT_EQUIPMENT;
            }
            return result == MaidEquipmentTransfer.Result.FAILED ? Result.HELD : Result.REJECTED;
        }
        var profile = maid.getCombatWeaponProfile();
        boolean visible = actor.combatObservation()
            .visible(target.getUniqueID());
        if (plan.face != null) actor.lookAtCombatPoint(plan.face);
        if (plan.macro == Macro.SHIELD) return defend(target, quota, visible);
        if (plan.macro == Macro.RETREAT || plan.macro == Macro.STRAFE_LEFT || plan.macro == Macro.STRAFE_RIGHT)
            jumpRequested = jumpPreparing = false;
        if (actor.shieldBehaviorRunning()) actor.stopUsingShield();
        if (isMovement(plan.macro)) {
            Result movement = move(plan.destination, quota);
            if (movement != Result.RUNNING) return movement;
        }
        if (!profile.supported() || profile.family != plan.family) {
            MaidCombatFeedback.unsupported(maid);
            return isMovement(plan.macro) ? Result.RUNNING : Result.REJECTED;
        }
        MaidCombatFeedback.supported(maid);
        maid.getCombatTaskState()
            .consumePreference();
        if (!visible) {
            actor.stopOwnedRangedUse();
            if (!isMovement(plan.macro)) return move(plan.destination, quota);
            return Result.RUNNING;
        }
        if (!quota.spend(Work.RAY) || !maid.getEntitySenses()
            .canSee(target)) {
            actor.stopOwnedRangedUse();
            return Result.WAIT_BUDGET;
        }
        if (!targets.canAttack(maid, target) || !targetArea(target.posX, target.posY, target.posZ))
            return Result.REJECTED;
        var view = actor.combatTargetView(target);
        actor.lookAtAttackTarget(view);
        if (profile.bow()) return bow(target, view, plan, quota);
        actor.stopOwnedRangedUse();
        if ((jumpRequested || jumpPreparing) && (jumpWeapon != maid.getHeldItem() || !target.getUniqueID()
            .equals(jumpTarget)
            || jumpEpoch != maid.getEquipmentEpoch()
                .generation()))
            jumpRequested = jumpPreparing = false;
        // LIGHT不发起新跳，但HOLD不能在上升期抢掉既有跳劈的下降命中窗口
        if (plan.macro == Macro.JUMP_STRIKE || (jumpRequested || jumpPreparing) && plan.macro == Macro.HOLD_ATTACK)
            return jump(target, view, plan, quota);
        if (actor.withinMeleeRange(view)) {
            if (!isMovement(plan.macro)) actor.eraseWalkTarget();
            return melee(target, view);
        }
        if (!isMovement(plan.macro) && !EnhancedRideController.stationary(maid)) {
            if (!quota.spend(Work.PATH)) return Result.WAIT_BUDGET;
            actor.walkToAttackTarget(view, APPROACH_SPEED);
        }
        return Result.RUNNING;
    }

    private Result bow(EntityLivingBase target, com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor.Target view,
        CombatPlan plan, CombatWorkQuota quota) {
        actor.rangedBow()
            .observe(target, true, maid.worldObj.getTotalWorldTime());
        if (!isMovement(plan.macro)) {
            if (EnhancedRideController.stationary(maid) || actor.distanceToTarget(view) <= BOW_STAND_DISTANCE)
                actor.eraseWalkTarget();
            else if (quota.spend(Work.PATH)) actor.walkToAttackTarget(view, APPROACH_SPEED);
            else return Result.WAIT_BUDGET;
        }
        if (actor.ownsRangedUse()) {
            if (actor.rangedUsedTicks() < MaidBowAttack.DRAW_TICKS || !maid.getCombatCadence()
                .ready()) return Result.RUNNING;
            actor.stopOwnedRangedUse();
            if (!authorized()) return Result.STALE;
            return actor.fireRangedAttackForPolicy(view, 1, () -> authorized() && targetIdentity(target))
                ? Result.FINISHED
                : Result.RUNNING;
        }
        if (maid.getItemUseState()
            .peek() == null && maid.getCombatCadence()
                .ready())
            actor.startRangedMainUse();
        return Result.RUNNING;
    }

    private Result melee(EntityLivingBase target, com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor.Target view) {
        if (!maid.getCombatCadence()
            .ready()) return Result.RUNNING;
        var use = maid.getItemUseState()
            .peek();
        if (use != null && !actor.shieldBehaviorRunning()) return Result.REJECTED;
        long before = actor.meleeNativeAccepted();
        actor.swingMainHand();
        actor.hurtTarget(view, () -> authorized() && targetIdentity(target));
        if (actor.meleeNativeAccepted() > before) {
            actor.combatObservation()
                .acceptedHit(target.getUniqueID(), maid.worldObj.getTotalWorldTime());
            return Result.FINISHED;
        }
        return maid.getCombatHitReceipt()
            .blocksTransfer() ? Result.HELD : Result.RUNNING;
    }

    public String lastJumpDiagnostic() {
        String value = jumpDiagnostic;
        if (headChecked) value += ":head=" + jumpHeadCheck.summary()
            + ":sweep="
            + sweepMinX
            + ","
            + sweepMinY
            + ","
            + sweepMinZ
            + "/"
            + sweepMaxX
            + ","
            + sweepMaxY
            + ","
            + sweepMaxZ;
        if (supportChecked) value += ":support=" + jumpSupportCheck.summary();
        return jumpMeleeResult == null ? value : value + ":" + jumpMeleeResult;
    }

    private Result jump(EntityLivingBase target, com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor.Target view,
        CombatPlan plan, CombatWorkQuota quota) {
        headChecked = supportChecked = false;
        jumpMeleeResult = null;
        if (!jumpRequested || !target.getUniqueID()
            .equals(jumpTarget)) {
            jumpDiagnostic = "prepare_gate";
            if ((!plan.simulated && !jumpPreparing) || !maid.getCombatCadence()
                .formatSupported()
                || maid.getCombatCadence()
                    .remainingTicks() > 8
                || jumpPreparing && maid.worldObj.getTotalWorldTime() - jumpAt > 8
                || !maid.onGround
                || maid.isInWater()
                || maid.isOnLadder()
                || EnhancedRideController.stationary(maid)
                || !actor.withinMeleeRange(view)
                || maid.isSprinting()
                || maid.isPotionActive(net.minecraft.potion.Potion.jump)
                || !Double.isFinite(jumpPreparing ? jumpExpectedDamage : plan.expectedDamage)
                || (jumpPreparing ? jumpExpectedDamage : plan.expectedDamage) <= 0) {
                jumpDiagnostic = "prepare_gate_rejected";
                return Result.REJECTED;
            }
            if (!jumpPreparing) {
                jumpPreparing = true;
                jumpExpectedDamage = plan.expectedDamage;
                jumpAt = maid.worldObj.getTotalWorldTime();
                jumpTarget = target.getUniqueID();
                jumpWeapon = maid.getHeldItem();
                jumpEpoch = maid.getEquipmentEpoch()
                    .generation();
            }
            actor.brakeForCombatJump();
            if (!maid.getCombatCadence()
                .ready()) {
                jumpDiagnostic = "waiting_cadence";
                return Result.RUNNING;
            }
            // 等待真实惯性自然减速，不把清除WALK当成水平速度已经归零
            if (maid.motionX * maid.motionX + maid.motionZ * maid.motionZ > .0001) {
                if (maid.worldObj.getTotalWorldTime() - jumpAt > 8) {
                    jumpDiagnostic = "brake_timeout";
                    return Result.REJECTED;
                }
                jumpDiagnostic = "braking";
                return Result.RUNNING;
            }
            // 检查整段残余水平惯性，而不是在实际垂直身体外再虚构固定0.35宽度
            double driftX = maid.motionX * JUMP_DRIFT_TICKS, driftZ = maid.motionZ * JUMP_DRIFT_TICKS;
            var sweep = maid.boundingBox.addCoord(driftX, HEAD_SWEEP, driftZ)
                .expand(JUMP_SWEEP_EPSILON, 0, JUMP_SWEEP_EPSILON);
            var clear = CombatSpace.boundedClear(maid, sweep, quota, jumpHeadCheck);
            headChecked = true;
            sweepMinX = sweep.minX;
            sweepMinY = sweep.minY;
            sweepMinZ = sweep.minZ;
            sweepMaxX = sweep.maxX;
            sweepMaxY = sweep.maxY;
            sweepMaxZ = sweep.maxZ;
            jumpDiagnostic = "head_checked";
            if (clear == CombatSpace.Result.BUDGET) return Result.WAIT_BUDGET;
            if (clear != CombatSpace.Result.SAFE) return Result.REJECTED;
            var standing = CombatSpace
                .safeStandingPoint(maid, maid.posX, maid.boundingBox.minY, maid.posZ, quota, jumpSupportCheck);
            supportChecked = true;
            jumpDiagnostic = "support_checked";
            if (standing == CombatSpace.Result.BUDGET) return Result.WAIT_BUDGET;
            if (standing != CombatSpace.Result.SAFE) return Result.REJECTED;
            maid.getJumpHelper()
                .setJumping();
            jumpDiagnostic = "requested";
            jumpRequested = true;
            jumpPreparing = false;
            airborne = false;
            jumpAt = maid.worldObj.getTotalWorldTime();
            return Result.RUNNING;
        }
        if (!maid.onGround) airborne = true;
        jumpDiagnostic = airborne ? "airborne_wait_descent" : "waiting_native_jump";
        if (airborne && maid.motionY < 0 && maid.fallDistance > 0 && actor.withinMeleeRange(view)) {
            Result result = melee(target, view);
            jumpDiagnostic = "descending_melee";
            jumpMeleeResult = result;
            if (result == Result.FINISHED) jumpRequested = jumpPreparing = false;
            return result;
        }
        if (maid.onGround && (airborne || maid.worldObj.getTotalWorldTime() - jumpAt > 4)) {
            jumpRequested = jumpPreparing = false;
            return Result.FINISHED;
        }
        return Result.RUNNING;
    }

    private Result defend(EntityLivingBase target, CombatWorkQuota quota, boolean visible) {
        actor.stopOwnedRangedUse();
        actor.eraseWalkTarget();
        if (!visible || !quota.spend(Work.RAY)
            || !maid.getEntitySenses()
                .canSee(target))
            return Result.REJECTED;
        if (actor.shieldBehaviorRunning()) {
            if (maid.worldObj.getTotalWorldTime() > actor.shieldBehaviorDeadline() || !shield.canStillUse(actor))
                shield.stop(actor);
        } else if (maid.getItemUseState()
            .peek() == null && shield.checkExtraStartConditions(actor)) shield.start(actor);
        return actor.shieldBehaviorRunning() ? Result.RUNNING : Result.REJECTED;
    }

    private Result move(Vec destination, CombatWorkQuota quota) {
        if (destination == null || EnhancedRideController.stationary(maid)) return Result.REJECTED;
        if (!Double.isFinite(destination.x) || !Double.isFinite(destination.y)
            || !Double.isFinite(destination.z)
            || !targetArea(destination.x, destination.y, destination.z)) return Result.REJECTED;
        var point = new Point(
            MathHelper.floor_double(destination.x),
            MathHelper.floor_double(destination.y + .5),
            MathHelper.floor_double(destination.z));
        long now = maid.worldObj.getTotalWorldTime();
        boolean continuing = approvedPoint != null && approvedPoint.x == point.x
            && approvedPoint.y == point.y
            && approvedPoint.z == point.z
            && Math.abs(approvedFootY - destination.y) < .0001
            && now >= routeApprovedAt
            && now - routeApprovedAt <= 8;
        if (!quota.spend(Work.PATH)) return Result.WAIT_BUDGET;
        if (!continuing) {
            var checked = CombatSpace.safeStandingPoint(maid, point.x + .5, destination.y, point.z + .5, quota);
            if (checked == CombatSpace.Result.BUDGET) return Result.WAIT_BUDGET;
            if (checked != CombatSpace.Result.SAFE) return Result.REJECTED;
            approvedPoint = point;
            approvedFootY = destination.y;
            routeApprovedAt = now;
        }
        actor.setPositionWalkAndLook(approvedPoint, APPROACH_SPEED, 0);
        return Result.RUNNING;
    }

    private boolean authorized() {
        if (!actor.isCurrent() || !maid.isUnifiedCombat()
            || maid.getTask() != MaidTask.ATTACK
            || !maid.getCombatTaskState()
                .available()
            || maid.guiOpening
            || maid.getLeashed()
            || maid.getHomeArea()
                .activity() != Activity.WORK)
            return false;
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = maid.getOwner();
        return MaidCombatTargeting.validOwner(maid, owner)
            && maid.getDistanceSqToEntity(owner) <= MaidCombatTargeting.OWNER_RADIUS_SQ;
    }

    private boolean targetIdentity(EntityLivingBase target) {
        return target != maid && target.worldObj == maid.worldObj
            && target.dimension == maid.dimension
            && target.isEntityAlive()
            && !target.isDead
            && !maid.isOnSameTeam(target)
            && maid.worldObj.getEntityByID(target.getEntityId()) == target;
    }

    private boolean targetArea(double x, double y, double z) {
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.contains(x, y, z);
        var owner = maid.getOwner();
        return owner != null && owner.getDistanceSq(x, y, z) <= MaidCombatTargeting.OWNER_RADIUS_SQ;
    }

    private static boolean isMovement(Macro macro) {
        return macro == Macro.APPROACH || macro == Macro.RETREAT
            || macro == Macro.STRAFE_LEFT
            || macro == Macro.STRAFE_RIGHT
            || macro == Macro.COVER_EDGE;
    }

    private void stopUse() {
        actor.stopOwnedRangedUse();
        if (actor.shieldBehaviorRunning()) actor.stopUsingShield();
    }

    public void cancel() {
        jumpRequested = jumpPreparing = airborne = false;
        jumpTarget = null;
        jumpWeapon = null;
        approvedPoint = null;
        // 只清本执行器状态；actor自己的生命周期清理负责撤销其旧租约
        if (actor.isCurrent()) {
            stopUse();
            actor.eraseWalkTarget();
        }
    }
}
