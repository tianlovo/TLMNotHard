package com.tianluoqaq.tlmnh.common.ai.enhanced;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAttackStrafingTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUseShieldTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskBowAttack;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor.Target;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernPerception;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 增强战斗独立选择与组合动作；合法性在世界提交回调后再次校验 */
public final class EnhancedCombatController {

    private static final int HIDDEN_MEMORY_TICKS = 100, MAX_CANDIDATES = 64;
    private static final float APPROACH_SPEED = .6F;
    private static final double RANGED_STAND_DISTANCE = 8;
    private final EntityMaid maid;
    private final ModernFollowCoreActor actor;
    private final MaidModernPerception perception;
    private final TaskAttack melee = new TaskAttack();
    private final TaskBowAttack ranged = new TaskBowAttack();
    private final MaidUseShieldTask shield = new MaidUseShieldTask();
    private final MaidAttackStrafingTask strafe = new MaidAttackStrafingTask();
    private EntityLivingBase ownedTarget;
    private Point lastSeen;
    private long lastSeenAt = Long.MIN_VALUE, attacks, releases;
    private int cooldown;
    private boolean strafing;

    public EnhancedCombatController(EntityMaid maid, ModernFollowCoreActor actor, MaidModernPerception perception) {
        this.maid = maid;
        this.actor = actor;
        this.perception = perception;
    }

    public void offer(MaidIntentFrame frame) {
        if (!permitted()) return;
        boolean bow = maid.getTask() == MaidTask.RANGED_ATTACK;
        if (ownedTarget != null && legal(ownedTarget, bow) && (visible(ownedTarget, bow) || remembers()))
            offer(frame, ownedTarget, bow);
        // 受击来源是本tick已发生事实，不等待周期附近清单刷新
        var attacker = maid.getAITarget();
        if (attacker != ownedTarget && legal(attacker, bow) && visible(attacker, bow)) offer(frame, attacker, bow);
        int count = 0;
        for (var candidate : perception.nearby()) {
            if (++count > MAX_CANDIDATES) break;
            if (candidate != ownedTarget && candidate != attacker && legal(candidate, bow) && visible(candidate, bow))
                offer(frame, candidate, bow);
        }
    }

    private void offer(MaidIntentFrame frame, EntityLivingBase target, boolean bow) {
        double distance = target == ownedTarget && !visible(target, bow) && lastSeen != null
            ? maid.getDistanceSq(lastSeen.x + .5, lastSeen.y, lastSeen.z + .5)
            : maid.getDistanceSqToEntity(target);
        frame.offer(
            Group.COMBAT,
            bow ? Kind.RANGED : Kind.MELEE,
            target.getUniqueID()
                .toString(),
            1 / (1 + Math.sqrt(distance)),
            target);
    }

    private boolean permitted() {
        if (!actor.isCurrent() || !maid.isTamed()
            || maid.getLeashed()
            || actor.isSleeping()
            || !actor.canBrainMoving() && !EnhancedRideController.stationary(maid)
            || maid.getHomeArea()
                .activity() != Activity.WORK)
            return false;
        var task = maid.getTask();
        if (task == MaidTask.ATTACK ? !actor.hasAssaultWeapon()
            : task != MaidTask.RANGED_ATTACK || !actor.hasBowAndArrow()) return false;
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid();
        var owner = actor.getOwner();
        return owner != null && !owner.isDeadOrDying()
            && !owner.isSpectator()
            && owner.getWorldIdentity() == actor.getWorldIdentity()
            && actor.closerThan(owner, 16);
    }

    private boolean legal(EntityLivingBase target, boolean bow) {
        if (target == null || target == maid
            || target.worldObj != maid.worldObj
            || target.dimension != maid.dimension
            || !target.isEntityAlive()
            || target.isDead
            || target.isEntityInvulnerable()
            || maid.worldObj.getEntityByID(target.getEntityId()) != target
            || maid.isOnSameTeam(target)
            || !(bow ? ranged.canAttack(maid, target) : melee.canAttack(maid, target))) return false;
        var owner = maid.getOwner();
        if (owner != null && owner.isOnSameTeam(target)) return false;
        var home = maid.getHomeArea();
        if (home.enabled()
            && (!home.sameDimension() || !home.containsMaid() || !home.contains(target.posX, target.posY, target.posZ)))
            return false;
        return !(bow ? ranged.farAway(target, maid) : melee.farAway(target, maid));
    }

    private boolean visible(EntityLivingBase target, boolean bow) {
        return bow ? ranged.canSee(maid, target)
            : IRangedAttackTask.targetConditionsTest(
                maid,
                target,
                Math.min(
                    16,
                    maid.getHomeArea()
                        .restrictionRadius()),
                ranged);
    }

    private boolean remembers() {
        long elapsed = maid.worldObj.getTotalWorldTime() - lastSeenAt;
        return lastSeen != null && elapsed >= 0 && elapsed <= HIDDEN_MEMORY_TICKS;
    }

    public void execute(EntityLivingBase selected, boolean bow) {
        if (!permitted() || !legal(selected, bow)) return;
        var mount = maid.ridingEntity;
        boolean sitting = maid.isSitting();
        java.util.function.BooleanSupplier mobility = () -> maid.ridingEntity == mount && maid.isSitting() == sitting;
        var memory = maid.getModernCombatMemory();
        if (memory.target() != selected) {
            if (!actor.setAttackTarget(actor.combatTargetView(selected)) || !actor.isCurrent()) return;
        }
        // 目标事件可以重定向，只使用事件完成后的实际对象并重新验证许可
        ownedTarget = memory.target();
        var target = ownedTarget;
        if (!actor.isCurrent() || !mobility.getAsBoolean()) return;
        if (!legal(target, bow)) {
            actor.eraseAttackTarget();
            ownedTarget = null;
            return;
        }
        var view = actor.combatTargetView(target);
        boolean seen = visible(target, bow);
        if (seen) {
            lastSeen = new Point(
                MathHelper.floor_double(target.posX),
                MathHelper.floor_double(target.posY),
                MathHelper.floor_double(target.posZ));
            lastSeenAt = maid.worldObj.getTotalWorldTime();
        } else {
            stopRanged();
            if (actor.shieldBehaviorRunning()) actor.stopUsingShield();
            if (!EnhancedRideController.stationary(maid) && remembers())
                actor.setPositionWalkAndLook(lastSeen, APPROACH_SPEED, 0);
            else actor.eraseWalkTarget();
            return;
        }
        actor.lookAtAttackTarget(view);
        if (bow) tickRanged(target, view, mobility);
        else tickMelee(target, view, mobility);
    }

    private void tickMelee(EntityLivingBase target, Target view, java.util.function.BooleanSupplier mobility) {
        stopRanged();
        if (EnhancedRideController.stationary(maid) || actor.withinMeleeRange(view)) actor.eraseWalkTarget();
        else actor.walkToAttackTarget(view, APPROACH_SPEED);
        var use = maid.getItemUseState();
        if (!actor.attackCoolingDown() && actor.withinMeleeRange(view)
            && (use.peek() == null || actor.shieldBehaviorRunning())) {
            actor.swingMainHand();
            attacks++;
            actor.hurtTarget(
                view,
                () -> mobility.getAsBoolean() && permitted() && legal(target, false) && visible(target, false));
            if (!actor.isCurrent()) return;
            double speed = actor.attackSpeed();
            actor.setAttackCooldown(speed > 0 ? (long) (20 / speed) : 20);
        }
        if (!actor.isCurrent()) return;
        if (actor.shieldBehaviorRunning()) {
            if (actor.worldGameTime() > actor.shieldBehaviorDeadline() || !shield.canStillUse(actor))
                shield.stop(actor);
        } else if (use.peek() == null && shield.checkExtraStartConditions(actor)) shield.start(actor);
    }

    private void tickRanged(EntityLivingBase target, Target view, java.util.function.BooleanSupplier mobility) {
        if (actor.shieldBehaviorRunning()) actor.stopUsingShield();
        actor.rangedBow()
            .observe(target, true, maid.worldObj.getTotalWorldTime());
        boolean stationary = EnhancedRideController.stationary(maid);
        boolean close = actor.distanceToTarget(view) <= RANGED_STAND_DISTANCE;
        if (stationary || close) actor.eraseWalkTarget();
        else actor.walkToAttackTarget(view, APPROACH_SPEED);
        if (!stationary && close) {
            if (!strafing && strafe.checkExtraStartConditions(actor)) {
                strafe.start(actor);
                strafing = true;
            }
            if (strafing) strafe.tick(actor);
        } else if (strafing) {
            strafe.stop(actor);
            strafing = false;
        }
        if (!actor.isCurrent()) return;
        actor.lookDirectlyAtRangedTarget(view);
        if (cooldown > 0) cooldown--;
        if (actor.ownsRangedUse()) {
            if (actor.rangedUsedTicks() >= MaidBowAttack.DRAW_TICKS) {
                actor.stopOwnedRangedUse();
                if (!actor.isCurrent()) return;
                releases++;
                actor.fireRangedAttackForPolicy(
                    view,
                    1,
                    () -> mobility.getAsBoolean() && permitted() && legal(target, true) && visible(target, true));
                if (actor.isCurrent()) cooldown = actor.shootCooldown();
            }
        } else if (maid.getItemUseState()
            .peek() == null && cooldown <= 0) actor.startRangedMainUse();
    }

    private void stopRanged() {
        actor.stopOwnedRangedUse();
        actor.rangedBow()
            .observe(null, false, maid.worldObj.getTotalWorldTime());
        if (strafing) strafe.stop(actor);
        strafing = false;
    }

    /** 旧引用先退出所有权，再触及可能随外部代码变化的内存接口 */
    public void cancel() {
        var target = ownedTarget;
        ownedTarget = null;
        lastSeen = null;
        lastSeenAt = Long.MIN_VALUE;
        cooldown = 0;
        strafing = false;
        if (target != null && maid.getModernCombatMemory()
            .target() == target) maid.getModernCombatMemory()
                .target(null);
    }

    public EntityLivingBase target() {
        return ownedTarget;
    }

    public Point lastSeenPosition() {
        return lastSeen;
    }

    public long attacks() {
        return attacks;
    }

    public long releases() {
        return releases;
    }
}
