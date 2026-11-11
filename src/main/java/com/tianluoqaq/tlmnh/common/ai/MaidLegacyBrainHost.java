package com.tianluoqaq.tlmnh.common.ai;

import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidOwnerSensor;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidTargetMemory;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidLifeSelector;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeController;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 旧原型控制器图与执行宿主，在旧策略执行或兼容性 getter 调用时按需创建 */
final class MaidLegacyBrainHost {

    private final EntityMaid maid;
    private final MaidBrainCoordinator coordinator;
    private final MaidActionRunner actionRunner;
    private final MaidOwnerSensor ownerSensor;
    private final MaidDangerSensor dangerSensor;
    private final MaidTelemetryChannel telemetry;
    final MaidFollowController follow;
    final MaidCombatController combat;
    private final CombatActor combatActor;
    final MaidBowAttack bow;
    final MaidLifeController life;
    final MaidHomeController home;
    final MaidPickupController pickup;
    final MaidPanicController panic;
    final MaidBreathController breath;
    final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner(true);
    final MaidBoundedPathPlanner combatPlanner = new MaidBoundedPathPlanner(true);
    private final MaidTargetMemory targetMemory = new MaidTargetMemory(MaidCombatController.HIDDEN_TIMEOUT);
    private final MaidIntentArbiter ordinaryArbiter = new MaidIntentArbiter();
    private Kind ordinaryIntent;
    private int ordinaryOffers;
    private String lastIntentState;

    MaidLegacyBrainHost(EntityMaid maid, MaidBrainCoordinator coordinator, MaidDangerSensor dangerSensor,
        int controllerSeed) {
        this.maid = maid;
        this.coordinator = coordinator;
        this.dangerSensor = dangerSensor;
        actionRunner = coordinator.getActionRunner();
        ownerSensor = coordinator.getOwnerSensor();
        telemetry = coordinator.getTelemetry();
        follow = new MaidFollowController(new EntityActor(), controllerSeed);
        home = new MaidHomeController(maid, actionRunner);
        panic = new MaidPanicController(maid, actionRunner, coordinator::isSuspended, coordinator::hasRecentHurt);
        breath = new MaidBreathController(maid, actionRunner);
        pickup = new MaidPickupController(maid, actionRunner, ownerSensor::findOwner);
        bow = new MaidBowAttack(maid);
        life = new MaidLifeController(
            maid,
            this::claimLifeControl,
            actionRunner::release,
            ownerSensor::findOwner,
            MaidLifeSelector::nearest);
        combatActor = new CombatActor();
        combat = new MaidCombatController(combatActor, controllerSeed);
    }

    void beginFrame(MaidPolicyContext context, long now) {
        targetMemory.beginFrame(maid.worldObj, context, now);
    }

    void clearOrdinaryIntents() {
        ordinaryArbiter.clear();
        ordinaryIntent = null;
    }

    void forgetTargets() {
        combatActor.forgetOwner();
        targetMemory.clear();
    }

    Throwable cancelControllers() {
        Throwable failure = MaidBrainCoordinator.cancel(breath::cancel, null);
        failure = MaidBrainCoordinator.cancel(panic::cancel, failure);
        failure = MaidBrainCoordinator.cancel(combat::cancel, failure);
        failure = MaidBrainCoordinator.cancel(pickup::cancel, failure);
        failure = MaidBrainCoordinator.cancel(life::cancel, failure);
        failure = MaidBrainCoordinator.cancel(bow::cancel, failure);
        failure = MaidBrainCoordinator.cancel(
            () -> maid.getMealController()
                .cancel(),
            failure);
        failure = MaidBrainCoordinator.cancel(follow::cancel, failure);
        return MaidBrainCoordinator.cancel(home::cancel, failure);
    }

    private boolean claimLifeControl(MaidActionRun run) {
        return actionRunner.claim(Owner.LIFE, run, life::cancel);
    }

    Kind getOrdinaryIntent() {
        return ordinaryIntent;
    }

    boolean offeredOrdinaryIntent(Kind kind) {
        return (ordinaryOffers & 1 << kind.ordinal()) != 0;
    }

    boolean applyCombatMovement() {
        return combatActor.applyMovement();
    }

    boolean tickSurvival() {
        boolean breathing = breath.tick();
        if (breathing) {
            ordinaryArbiter.clear();
            ordinaryIntent = null;
            panic.cancel();
            combat.cancel();
            bow.cancel();
            pickup.cancel();
        }
        return breathing;
    }

    boolean tickPanic() {
        boolean panicking = panic.tick();
        if (panicking) {
            ordinaryArbiter.clear();
            ordinaryIntent = null;
            combat.cancel();
            bow.cancel();
            pickup.cancel();
        }
        return panicking;
    }

    boolean tickCombat() {
        MaidLegacyCombatEquipment.select(maid);
        boolean fighting = combat.tick();
        if (fighting) {
            ordinaryArbiter.clear();
            ordinaryIntent = null;
        }
        maid.getShieldController()
            .updateActiveTarget(maid.getAttackTarget());
        return fighting;
    }

    boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
        boolean living = life.tick(context, token, permitted);
        if (!permitted || living) pickup.cancel();
        return living;
    }

    void tickFollow() {
        if (maid.getHomeArea()
            .enabled()) {
            follow.cancel();
            home.tick();
            if (actionRunner.getOwner() == Owner.HOME) pickup.cancel();
            else pickup.tick(!dangerSensor.hasKnownDanger());
        } else {
            home.cancel();
            if (actionRunner.getOwner() == Owner.FOLLOW) {
                follow.tick();
                if (actionRunner.getOwner() == Owner.FOLLOW) {
                    pickup.cancel();
                    return;
                }
            }
            if (pickup.tick(!dangerSensor.hasKnownDanger())) follow.cancel();
            else follow.tick();
        }
    }

    void tickOrdinaryIntents(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
        var frame = new MaidIntentFrame(context, token, maid.worldObj.getTotalWorldTime());
        if (home.wantsReturn()) frame.offer(Group.COMMAND, Kind.HOME, "home", 1, null);
        var owner = ownerSensor.findOwner();
        var lifeOptions = permitted ? life.prepareIntents(context, token)
            : java.util.Collections.<MaidLifeIntent>emptyList();
        // 合法生活保留现有委托范围，不用5格跟随起距截断8格食物活动
        if (lifeOptions.isEmpty() && !maid.getHomeArea()
            .enabled()
            && MaidOwnerSensor.isValid(maid, owner)
            && !coordinator.isSuspended()
            && maid.isTamed()
            && maid.isEntityAlive()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && maid.getAttackTarget() == null) {
            double distance = maid.getDistanceSqToEntity(owner);
            double threshold = actionRunner.getOwner() == Owner.FOLLOW ? MaidFollowController.STOP_DISTANCE_SQ
                : MaidFollowController.START_DISTANCE_SQ;
            if (distance > threshold && distance <= MaidFollowController.MAX_DISTANCE_SQ) frame.offer(
                Group.COMMAND,
                Kind.FOLLOW,
                owner.getUniqueID()
                    .toString(),
                1,
                null);
        }
        if (permitted) {
            for (var intent : lifeOptions) {
                var candidate = intent.getCandidate();
                boolean bed = intent.getAction() == MaidLifeIntent.Action.BED;
                frame.offer(
                    bed ? Group.CARE : Group.AUTONOMOUS,
                    bed ? Kind.SLEEP : Kind.SNACK,
                    candidate.getDimension() + ":"
                        + candidate.getX()
                        + ":"
                        + candidate.getY()
                        + ":"
                        + candidate.getZ()
                        + ":"
                        + intent.getAction(),
                    proximity(intent.getDistanceSq()),
                    intent);
            }
            for (var entity : pickup.prepareIntents()) frame.offer(
                Group.AUTONOMOUS,
                Kind.PICKUP,
                entity.getUniqueID()
                    .toString(),
                proximity(maid.getDistanceSqToEntity(entity)),
                entity);
        }
        ordinaryOffers = frame.kindMask();
        var selected = ordinaryArbiter.choose(frame);
        ordinaryIntent = selected == null ? null : selected.kind;
        String state = selected == null ? "none"
            : selected.kind + ":" + selected.target + ":" + ordinaryArbiter.reason();
        if (!state.equals(lastIntentState)) {
            telemetry.state("ordinary_intent", selected == null ? "" : selected.target, state);
            lastIntentState = state;
        }
        boolean living = selected != null && (selected.kind == Kind.SLEEP || selected.kind == Kind.SNACK);
        if (!living) life.tick(context, token, false);
        if (selected == null || selected.kind != Kind.FOLLOW) follow.cancel();
        if (selected == null || selected.kind != Kind.HOME) home.cancel();
        if (selected == null || selected.kind != Kind.PICKUP) {
            if (pickup.getTarget() != null) pickup.cancel();
        }
        if (!frame.accepts(selected)) return;
        switch (selected.kind) {
            case SLEEP, SNACK -> life.tickSelected(context, token, (MaidLifeIntent) selected.payload);
            case PICKUP -> pickup.tickSelected((net.minecraft.entity.Entity) selected.payload);
            case FOLLOW -> follow.tick();
            case HOME -> home.tick();
        }
    }

    private static double proximity(double distanceSq) {
        return 1 / (1 + Math.sqrt(Math.max(0, distanceSq)));
    }

    private final class CombatActor implements MaidCombatController.Actor {

        private EntityPlayer owner;
        private EntityLivingBase target;
        private PathEntity ownedPath;
        private UUID ignoredTarget;
        private long ignoreUntil, nextOwnerScan;
        private MaidTask selectedTask;
        private MaidActionRun controlRun;
        private final MaidStrafingState strafing = new MaidStrafingState();
        private long movementWorldTick = Long.MIN_VALUE;

        @Override
        public void positionInRange() {
            if (maid.getCombatModeTask() != MaidTask.RANGED_ATTACK || !controlValid()) return;
            var area = maid.getHomeArea();
            boolean boundary = !area.enabled() && owner != null
                && maid.getDistanceSqToEntity(owner) >= area.radius() * area.radius();
            strafing.tick(
                Math.sqrt(distanceSq()),
                16,
                boundary,
                () -> maid.getRNG()
                    .nextFloat());
            movementWorldTick = maid.worldObj.getTotalWorldTime();
        }

        @Override
        public void stopPositioning() {
            strafing.stop();
            movementWorldTick = Long.MIN_VALUE;
        }

        @Override
        public boolean prepareWhileChasing() {
            return maid.getCombatModeTask() == MaidTask.RANGED_ATTACK;
        }

        private boolean applyMovement() {
            if (maid.worldObj == null || maid.worldObj.isRemote
                || movementWorldTick != maid.worldObj.getTotalWorldTime()) return false;
            movementWorldTick = Long.MIN_VALUE;
            if (coordinator.isSuspended() || coordinator.isUnloaded()
                || !controlValid()
                || !targetValid()
                || blockedReason() != null) return false;
            maid.rotationYaw = maid.rotationYawHead;
            float forward = strafing.forward(), strafe = strafing.strafe();
            if (!safeStep(forward, strafe)) {
                // 现代 MoveControl 对不可走侧向退回正前方；前方也不可走时保留原地
                forward = safeStep(1, 0) ? 1 : 0;
                strafe = 0;
            }
            maid.setAIMoveSpeed(
                (float) maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue() * .25F);
            maid.moveForward = forward;
            maid.moveStrafing = strafe;
            return true;
        }

        private boolean safeStep(float forward, float strafe) {
            if (forward == 0 && strafe == 0) return true;
            double yaw = maid.rotationYaw * Math.PI / 180;
            double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * .5;
            double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * .5;
            var box = maid.boundingBox.copy()
                .offset(dx, 0, dz);
            if (!maid.getHomeArea()
                .contains(maid.posX + dx, maid.posY, maid.posZ + dz)) return false;
            for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX); x++)
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ); z++)
                    for (int y = MathHelper.floor_double(box.minY) - 1; y <= MathHelper.floor_double(box.maxY); y++) {
                        if (!maid.worldObj.blockExists(x, y, z)) return false;
                        var block = maid.worldObj.getBlock(x, y, z);
                        if (block.getMaterial()
                            .isLiquid() || block == Blocks.fire
                            || block == Blocks.cactus) return false;
                    }
            return maid.worldObj.getCollidingBoundingBoxes(maid, box)
                .isEmpty()
                && !maid.worldObj.getCollidingBoundingBoxes(
                    maid,
                    box.copy()
                        .offset(0, -.6, 0))
                    .isEmpty();
        }

        private void forgetOwner() {
            owner = null;
            nextOwnerScan = 0;
        }

        @Override
        public MaidCombatController.Status blockedReason() {
            if (maid.worldObj.isRemote || !maid.isEntityAlive()
                || !maid.isTamed()
                || coordinator.isSuspended()
                || maid.isSitting()
                || maid.ridingEntity != null
                || maid.getLeashed()) return MaidCombatController.Status.HARD_STOP;
            if (selectedTask != null && selectedTask != maid.getTask()) return MaidCombatController.Status.DISABLED;
            if (maid.getTask() != MaidTask.ATTACK && maid.getCombatModeTask() != MaidTask.RANGED_ATTACK)
                return MaidCombatController.Status.DISABLED;
            var area = maid.getHomeArea();
            if (area.enabled() && (!area.sameDimension() || !area.containsMaid() || area.activity() != Activity.WORK))
                return MaidCombatController.Status.HARD_STOP;
            if (maid.getCombatModeTask() == MaidTask.RANGED_ATTACK ? !MaidBowAttack.hasBow(maid) || !bow.hasAmmunition()
                : !MaidCombatTargeting.hasMeleeWeapon(maid)) return MaidCombatController.Status.NO_WEAPON;
            if (coordinator.decisionTick() >= nextOwnerScan) {
                nextOwnerScan = coordinator.decisionTick() + MaidCombatController.SCAN_INTERVAL;
                owner = ownerSensor.findOwner();
            }
            if (!area.enabled() && !MaidCombatTargeting.validOwner(maid, owner))
                return MaidCombatController.Status.NO_OWNER;
            if (!area.enabled() && maid.getDistanceSqToEntity(owner) > MaidCombatTargeting.OWNER_RADIUS_SQ)
                return MaidCombatController.Status.HARD_STOP;
            return null;
        }

        @Override
        public boolean hasTarget() {
            return target != null;
        }

        @Override
        public boolean acquireTarget() {
            if (adopt(maid.getAttackTarget()) || adopt(maid.getAITarget())
                || owner != null && (adopt(owner.getAITarget()) || adopt(owner.getLastAttacker()))) return true;
            EntityLivingBase closest = null;
            double distance = Double.MAX_VALUE;
            boolean recording = telemetry.isEnabled();
            long started = recording ? System.nanoTime() : 0;
            var candidates = maid.worldObj
                .getEntitiesWithinAABB(EntityLivingBase.class, maid.boundingBox.expand(16, 8, 16));
            int inspected = 0;
            for (Object value : candidates) {
                if (++inspected > 64) break;
                if (value instanceof EntityLivingBase candidate && usable(candidate)) {
                    double candidateDistance = maid.getDistanceSqToEntity(candidate);
                    if (candidateDistance < distance) {
                        closest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
            telemetry.scan(
                "combat_targets",
                candidates.size(),
                Math.min(inspected, 64),
                "native_aabb_returned_list;partial=" + (candidates.size() > Math.min(inspected, 64)));
            if (recording) telemetry.work("combat_targets", WorkComponent.SENSOR, System.nanoTime() - started);
            return closest != null && adopt(closest);
        }

        private boolean usable(EntityLivingBase candidate) {
            if (candidate == null) return false;
            if (candidate.getUniqueID()
                .equals(ignoredTarget) && coordinator.decisionTick() < ignoreUntil) {
                telemetry.candidate("combat", candidate, false, "temporarily_ignored");
                return false;
            }
            if (!MaidCombatTargeting.canAttack(maid, owner, candidate)) {
                telemetry.candidate("combat", candidate, false, "target_filter");
                return false;
            }
            boolean visible = maid.getEntitySenses()
                .canSee(candidate);
            telemetry.candidate("combat", candidate, visible, visible ? "visible" : "unseen");
            return visible;
        }

        private boolean adopt(EntityLivingBase candidate) {
            if (!usable(candidate) || !rememberVisible(candidate)) return false;
            target = candidate;
            selectedTask = maid.getTask();
            maid.setAttackTarget(candidate);
            telemetry
                .state("combat_target_selected", MaidTelemetryChannel.id(candidate), "task=" + selectedTask.getId());
            return true;
        }

        private boolean rememberVisible(EntityLivingBase candidate) {
            return targetMemory.observeVisible(
                candidate.getUniqueID(),
                candidate,
                candidate.posX,
                candidate.boundingBox.minY,
                candidate.posZ,
                candidate.boundingBox.minY + candidate.getEyeHeight());
        }

        private MaidTargetMemory.Observation lastSeen() {
            return target == null ? null : targetMemory.recall(target.getUniqueID(), target);
        }

        @Override
        public boolean targetValid() {
            return target != null && maid.getAttackTarget() == target
                && MaidCombatTargeting.canAttack(maid, owner, target);
        }

        @Override
        public boolean targetVisible() {
            boolean visible = maid.getEntitySenses()
                .canSee(target);
            if (visible) visible = rememberVisible(target);
            if (maid.getCombatModeTask() == MaidTask.RANGED_ATTACK)
                bow.observe(target, visible, coordinator.decisionTick());
            return visible;
        }

        @Override
        public double distanceSq() {
            var observation = lastSeen();
            return observation == null ? Double.NaN
                : maid.getDistanceSq(observation.getX(), observation.getFootY(), observation.getZ());
        }

        @Override
        public double reachSq() {
            return maid.getCombatModeTask() == MaidTask.RANGED_ATTACK ? MaidBowAttack.RANGE_SQ
                : MaidCombatTargeting.meleeReachSq(maid, target);
        }

        @Override
        public boolean claimControl() {
            var proposed = actionRunner.newRun(maid.worldObj, maid.getTask());
            if (!actionRunner.claim(Owner.COMBAT, proposed, combat::cancel)) return false;
            controlRun = proposed;
            return controlValid();
        }

        @Override
        public boolean controlValid() {
            return actionRunner.isOwned(controlRun, maid.worldObj, maid.getTask());
        }

        @Override
        public void releaseControl() {
            var released = controlRun;
            controlRun = null;
            actionRunner.release(released);
        }

        @Override
        public void revokeControl() {
            if (controlRun != null) controlRun.cancel();
        }

        @Override
        public void lookAtTarget() {
            if (!controlValid()) return;
            var observation = lastSeen();
            if (observation == null) return;
            maid.getLookHelper()
                .setLookPosition(
                    observation.getX(),
                    observation.getEyeY(),
                    observation.getZ(),
                    30,
                    maid.getVerticalFaceSpeed());
        }

        @Override
        public boolean hasPath() {
            return ownedPath != null
                && com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(
                    maid.getNavigator()
                        .getPath(),
                    ownedPath)
                && !maid.getNavigator()
                    .noPath();
        }

        @Override
        public PathResult approachTarget() {
            if (!controlValid() || !targetValid()) return PathResult.UNREACHABLE;
            var observation = lastSeen();
            if (observation == null) return PathResult.UNREACHABLE;
            double dx = observation.getX() - maid.posX, dy = observation.getFootY() - maid.posY,
                dz = observation.getZ() - maid.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fraction = distance > 16 ? 16 / distance : 1;
            var result = combatPlanner
                .request(maid, maid.posX + dx * fraction, maid.posY + dy * fraction, maid.posZ + dz * fraction, 1.0D);
            if (result == PathResult.STARTED) ownedPath = maid.getNavigator()
                .getPath();
            return result;
        }

        @Override
        public void stopNavigation() {
            if (ownedPath != null && com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(
                maid.getNavigator()
                    .getPath(),
                ownedPath))
                maid.getNavigator()
                    .clearPathEntity();
            ownedPath = null;
        }

        @Override
        public boolean attack() {
            if (!controlValid() || !targetValid()) return false;
            if (maid.getCombatModeTask() == MaidTask.RANGED_ATTACK) return bow.fire(target);
            maid.swingItem();
            return maid.attackEntityAsMob(target);
        }

        @Override
        public boolean prepareAttack() {
            return maid.getCombatModeTask() != MaidTask.RANGED_ATTACK || bow.prepare();
        }

        @Override
        public void interruptAttack() {
            bow.interrupt();
        }

        @Override
        public int attackInterval() {
            return maid.getCombatModeTask() == MaidTask.RANGED_ATTACK ? 2 : MaidCombatController.ATTACK_INTERVAL;
        }

        @Override
        public void release(boolean suppressTarget) {
            targetMemory.forgetTarget();
            if (target != null) {
                if (suppressTarget) {
                    ignoredTarget = target.getUniqueID();
                    ignoreUntil = coordinator.decisionTick() + 100;
                }
                if (maid.getAttackTarget() == target) maid.setAttackTarget(null);
            }
            stopNavigation();
            bow.cancel();
            target = null;
            selectedTask = null;
        }

        @Override
        public double x() {
            return maid.posX;
        }

        @Override
        public double y() {
            return maid.posY;
        }

        @Override
        public double z() {
            return maid.posZ;
        }
    }

    private final class EntityActor implements MaidFollowController.Actor {

        private EntityPlayer owner;
        private PathEntity ownedPath;
        private MaidActionRun controlRun;

        @Override
        public boolean claimControl() {
            var proposed = actionRunner.newRun(maid.worldObj, maid.getTask());
            if (!actionRunner.claim(Owner.FOLLOW, proposed, follow::cancel)) return false;
            controlRun = proposed;
            return controlValid();
        }

        @Override
        public boolean controlValid() {
            return actionRunner.isOwned(controlRun, maid.worldObj, maid.getTask());
        }

        @Override
        public void releaseControl() {
            var released = controlRun;
            controlRun = null;
            actionRunner.release(released);
        }

        @Override
        public void revokeControl() {
            if (controlRun != null) controlRun.cancel();
        }

        @Override
        public Status stopReason() {
            if (maid.worldObj.isRemote) return Status.CLIENT;
            if (!maid.isEntityAlive()) return Status.DEAD;
            if (coordinator.isSuspended()) return Status.SUSPENDED;
            if (maid.ridingEntity != null) return Status.RIDING;
            if (maid.isSitting()) return Status.SITTING;
            if (maid.hasPose(MaidPose.SLEEPING)) return Status.SLEEPING;
            if (maid.getLeashed()) return Status.LEASHED;
            if (!maid.isTamed()) return Status.UNTAMED;
            if (maid.getAttackTarget() != null) return Status.BUSY;
            if (maid.getHomeArea()
                .enabled()) return Status.BUSY;
            return null;
        }

        @Override
        public boolean refreshOwner() {
            owner = ownerSensor.findOwner();
            return owner != null;
        }

        @Override
        public void forgetOwner() {
            owner = null;
        }

        @Override
        public boolean ownerValid() {
            return MaidOwnerSensor.isValid(maid, owner);
        }

        @Override
        public double distanceToOwnerSq() {
            return maid.getDistanceSqToEntity(owner);
        }

        @Override
        public double ownerX() {
            return owner.posX;
        }

        @Override
        public double ownerY() {
            return owner.boundingBox.minY;
        }

        @Override
        public double ownerZ() {
            return owner.posZ;
        }

        @Override
        public double x() {
            return maid.posX;
        }

        @Override
        public double y() {
            return maid.posY;
        }

        @Override
        public double z() {
            return maid.posZ;
        }

        @Override
        public void lookAtOwner() {
            if (!controlValid()) return;
            maid.getLookHelper()
                .setLookPositionWithEntity(owner, 10.0F, maid.getVerticalFaceSpeed());
        }

        @Override
        public boolean hasPath() {
            return !maid.getNavigator()
                .noPath();
        }

        @Override
        public PathResult requestPath() {
            if (!controlValid() || !ownerValid()) return PathResult.UNREACHABLE;
            double dx = ownerX() - maid.posX, dy = ownerY() - maid.posY, dz = ownerZ() - maid.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fraction = distance > 16 ? 16 / distance : 1;
            double x = maid.posX + dx * fraction, y = maid.posY + dy * fraction, z = maid.posZ + dz * fraction;
            var result = planner.request(maid, x, y, z, 1.0D);
            if (result != PathResult.STARTED) return result;
            ownedPath = maid.getNavigator()
                .getPath();
            return PathResult.STARTED;
        }

        @Override
        public void stopNavigation() {
            var current = maid.getNavigator()
                .getPath();
            if (com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership.owns(current, ownedPath)) {
                maid.getNavigator()
                    .clearPathEntity();
            }
            // 原版移动和注视请求每tick消费；取消后不再提交，也不覆盖其他动作的新请求
            ownedPath = null;
        }
    }
}
