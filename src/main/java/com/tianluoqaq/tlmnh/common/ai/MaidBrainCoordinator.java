package com.tianluoqaq.tlmnh.common.ai;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeController;

/** 唯一服务端任务入口，战斗优先打断生活动作，路径和目标由当前动作独占 */
public final class MaidBrainCoordinator extends EntityAIBase {

    private final EntityMaid maid;
    private final MaidFollowController follow;
    private final MaidCombatController combat;
    private final MaidBowAttack bow;
    private final MaidLifeController life;
    private final MaidTelemetryChannel telemetry;
    private MaidPolicyRuntime policy;
    private World observedWorld;
    private String observedOwner;
    private String lastPolicyState;
    private boolean transitioningPolicy, lifecycleFailed, unloaded, persistentReadFailed;
    private int persistentReadDepth;
    private final BooleanSupplier lifePermission;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private final MaidBoundedPathPlanner combatPlanner = new MaidBoundedPathPlanner();
    private boolean suspended;
    private long decisionTick;
    private long nextDangerScan;
    private boolean visibleDanger;

    public MaidBrainCoordinator(EntityMaid maid) {
        this.maid = maid;
        telemetry = new MaidTelemetryChannel(maid);
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        follow = new MaidFollowController(new EntityActor(), maid.getEntityId());
        bow = new MaidBowAttack(maid);
        life = new MaidLifeController(maid, follow::cancel);
        combat = new MaidCombatController(new CombatActor(), maid.getEntityId());
        lifePermission = () -> !suspended && !hasVisibleDanger();
        setMutexBits(7);
    }

    @Override
    public boolean shouldExecute() {
        return !maid.worldObj.isRemote && maid.isEntityAlive();
    }

    @Override
    public boolean continueExecuting() {
        return shouldExecute();
    }

    @Override
    public void updateTask() {
        if (MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) {
            onUnload();
            return;
        }
        if (unloaded && !onRegistered()) return;
        decisionTick++;
        var runtime = getPolicyRuntime();
        if (runtime == null) return;
        if (observedWorld != maid.worldObj) runtime.invalidate(InvalidationReason.WORLD_CHANGED);
        else if (!Objects.equals(observedOwner, maid.func_152113_b()))
            runtime.invalidate(InvalidationReason.OWNER_CHANGED);
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : 0;
        try {
            var result = runtime.tick(lifePermission);
            if (recording) {
                String state = runtime.getProfile()
                    .getId() + ":"
                    + runtime.isReady()
                    + ":"
                    + runtime.getContext()
                        .getEpoch()
                    + ":"
                    + runtime.getLastInvalidationReason()
                    + ":"
                    + result;
                if (!state.equals(lastPolicyState)) {
                    telemetry.state("policy", "", state);
                    lastPolicyState = state;
                }
            }
        } finally {
            if (recording) telemetry.work("brain", WorkComponent.BRAIN, System.nanoTime() - started);
        }
    }

    @Override
    public void resetTask() {
        var runtime = getPolicyRuntime();
        if (runtime != null) runtime.invalidate(InvalidationReason.RESET);
    }

    /** 延迟绑定持久UUID；原生NBT读取会在实体构造之后恢复UUID */
    public MaidPolicyRuntime getPolicyRuntime() {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || transitioningPolicy
            || lifecycleFailed
            || unloaded
            || persistentReadDepth > 0
            || MaidBrainLifecycle.isWorldUnloading(maid.worldObj)) return policy;
        if (policy != null && !policy.getContext()
            .getMaidId()
            .equals(maid.getUniqueID())) {
            retireCurrent(InvalidationReason.IDENTITY_CHANGED);
            policy = null;
        }
        if (policy == null) policy = new MaidPolicyRuntime(maid.getUniqueID(), new MaidPolicyRuntime.LegacyActions() {

            @Override
            public boolean tickCombat() {
                return combat.tick();
            }

            @Override
            public boolean tickLife(boolean permitted) {
                return life.tick(permitted);
            }

            @Override
            public void tickFollow() {
                follow.tick();
            }

            @Override
            public void cancelAll() {
                cancelControllers();
            }
        });
        return policy;
    }

    /** 元数据观察不创建或恢复策略，尚未创建时返回 null */
    public MaidPolicyRuntime peekPolicyRuntime() {
        return policy;
    }

    /** 仅已真正登记回世界的对象可以结束卸载阻断，清理失败不会自动恢复 */
    public boolean onRegistered() {
        var world = maid.worldObj;
        if (world == null || world.isRemote
            || !maid.isEntityAlive()
            || lifecycleFailed
            || transitioningPolicy
            || persistentReadDepth > 0
            || MaidBrainLifecycle.isWorldUnloading(world)
            || world.getEntityByID(maid.getEntityId()) != maid
            || !world.loadedEntityList.contains(maid)
            || !maid.addedToChunk
            || !world.getChunkProvider()
                .chunkExists(maid.chunkCoordX, maid.chunkCoordZ)
            || !world.getChunkFromChunkCoords(maid.chunkCoordX, maid.chunkCoordZ).isChunkLoaded) return false;
        if (unloaded) {
            unloaded = false;
            policy = null;
            refreshLifecycleObservations();
        }
        return true;
    }

    /** 外层 native NBT 入口先调用，child 入口嵌套时只增加深度 */
    public void beforePersistentRead() {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        persistentReadDepth++;
        if (persistentReadDepth > 1) return;
        persistentReadFailed = false;
        try {
            if (transitioningPolicy) throw new IllegalStateException("Persistent read during policy cleanup");
            retireCurrent(InvalidationReason.IDENTITY_CHANGED);
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = persistentReadFailed = true;
            throw failure;
        } finally {
            if (telemetry.isEnabled()) telemetry.invalidate("actor_persistent_read_during_recording");
        }
    }

    public void afterPersistentRead(boolean successful) {
        if (maid.worldObj == null || maid.worldObj.isRemote) return;
        if (persistentReadDepth <= 0) throw new IllegalStateException("Unbalanced persistent read boundary");
        if (!successful) lifecycleFailed = persistentReadFailed = true;
        persistentReadDepth--;
        if (persistentReadDepth == 0 && !persistentReadFailed && !lifecycleFailed) {
            if (!unloaded) policy = null;
            refreshLifecycleObservations();
        }
    }

    public boolean isLifecycleFailed() {
        return lifecycleFailed;
    }

    private void refreshLifecycleObservations() {
        observedWorld = maid.worldObj;
        observedOwner = maid.func_152113_b();
        nextDangerScan = 0;
        visibleDanger = false;
        lastPolicyState = null;
    }

    public MaidTelemetryChannel getTelemetry() {
        return telemetry;
    }

    public void onUnload() {
        if (maid.worldObj == null || maid.worldObj.isRemote || unloaded) return;
        unloaded = true;
        try {
            retireCurrent(InvalidationReason.UNLOADED);
        } finally {
            if (telemetry.isEnabled()) telemetry.invalidate("actor_unloaded_during_recording");
        }
    }

    private void retireCurrent(InvalidationReason reason) {
        if (transitioningPolicy) {
            if (policy != null) policy.retire(reason);
            return;
        }
        transitioningPolicy = true;
        try {
            if (policy == null) cancelControllers();
            else policy.retire(reason);
        } catch (RuntimeException | Error failure) {
            lifecycleFailed = true;
            throw failure;
        } finally {
            transitioningPolicy = false;
        }
    }

    private void cancelControllers() {
        Throwable failure = cancel(combat::cancel, null);
        failure = cancel(life::cancel, failure);
        failure = cancel(bow::cancel, failure);
        failure = cancel(follow::cancel, failure);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static Throwable cancel(Runnable action, Throwable first) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (first == null) return failure;
            if (first != failure) first.addSuppressed(failure);
        }
        return first;
    }

    public MaidFollowController getFollowController() {
        return follow;
    }

    public MaidBoundedPathPlanner.Attempt getLastPathAttempt() {
        return planner.getLastAttempt();
    }

    public MaidCombatController getCombatController() {
        return combat;
    }

    public MaidBowAttack getBowAttack() {
        return bow;
    }

    public MaidLifeController getLifeController() {
        return life;
    }

    public MaidBoundedPathPlanner.Attempt getLastCombatPathAttempt() {
        return combatPlanner.getLastAttempt();
    }

    /** 供受控场景暂时让出行为，恢复后重新感知，不持久化为玩家玩法状态 */
    public void setSuspended(boolean suspended) {
        if (maid.worldObj.isRemote) return;
        if (this.suspended != suspended) {
            this.suspended = suspended;
            var runtime = getPolicyRuntime();
            if (runtime != null) runtime.invalidate(InvalidationReason.SUSPENSION_CHANGED);
        }
    }

    public boolean isSuspended() {
        return suspended;
    }

    /** 生活中断不依赖所选战斗任务或武器，扫描有界且不提交路径 */
    private boolean hasVisibleDanger() {
        if (maid.hurtTime > 0) return true;
        if (decisionTick < nextDangerScan) return visibleDanger;
        nextDangerScan = decisionTick + 10;
        visibleDanger = false;
        boolean recording = telemetry.isEnabled();
        long started = recording ? System.nanoTime() : 0;
        var candidates = maid.worldObj.getEntitiesWithinAABB(IMob.class, maid.boundingBox.expand(8, 4, 8));
        int inspected = 0;
        for (Object value : candidates) {
            if (++inspected > 32) break;
            boolean accepted = value instanceof EntityLivingBase entity && entity instanceof IMob
                && entity.isEntityAlive()
                && maid.getEntitySenses()
                    .canSee(entity);
            if (value instanceof EntityLivingBase entity)
                telemetry.candidate("danger", entity, accepted, "visible_hostile_filter");
            if (accepted) {
                visibleDanger = true;
                break;
            }
        }
        telemetry.scan(
            "danger",
            candidates.size(),
            Math.min(inspected, 32),
            "native_aabb_returned_list;partial=" + (candidates.size() > Math.min(inspected, 32)));
        if (recording) telemetry.work("danger", WorkComponent.SENSOR, System.nanoTime() - started);
        return visibleDanger;
    }

    private final class CombatActor implements MaidCombatController.Actor {

        private EntityPlayer owner;
        private EntityLivingBase target;
        private PathEntity ownedPath;
        private UUID ignoredTarget;
        private long ignoreUntil, nextOwnerScan;
        private double seenX, seenY, seenZ;
        private MaidTask selectedTask;

        @Override
        public MaidCombatController.Status blockedReason() {
            if (maid.worldObj.isRemote || !maid.isEntityAlive()
                || !maid.isTamed()
                || suspended
                || maid.isSitting()
                || maid.ridingEntity != null
                || maid.getLeashed()) return MaidCombatController.Status.HARD_STOP;
            if (selectedTask != null && selectedTask != maid.getTask()) return MaidCombatController.Status.DISABLED;
            if (maid.getTask() != MaidTask.ATTACK && maid.getTask() != MaidTask.RANGED_ATTACK)
                return MaidCombatController.Status.DISABLED;
            if (maid.getTask() == MaidTask.RANGED_ATTACK ? !MaidBowAttack.hasBow(maid) || !bow.hasAmmunition()
                : !MaidCombatTargeting.hasMeleeWeapon(maid)) return MaidCombatController.Status.NO_WEAPON;
            if (decisionTick >= nextOwnerScan) {
                nextOwnerScan = decisionTick + MaidCombatController.SCAN_INTERVAL;
                owner = null;
                int inspected = 0;
                for (Object value : maid.worldObj.playerEntities) {
                    inspected++;
                    if (value instanceof EntityPlayer player && MaidCombatTargeting.validOwner(maid, player)) {
                        owner = player;
                        break;
                    }
                }
                telemetry.scan("combat_owner", maid.worldObj.playerEntities.size(), inspected, "world_player_list");
            }
            if (!MaidCombatTargeting.validOwner(maid, owner)) return MaidCombatController.Status.NO_OWNER;
            if (maid.getDistanceSqToEntity(owner) > MaidCombatTargeting.OWNER_RADIUS_SQ)
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
                || adopt(owner.getAITarget())
                || adopt(owner.getLastAttacker())) return true;
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
                .equals(ignoredTarget) && decisionTick < ignoreUntil) {
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
            if (!usable(candidate)) return false;
            target = candidate;
            selectedTask = maid.getTask();
            maid.setAttackTarget(candidate);
            telemetry
                .state("combat_target_selected", MaidTelemetryChannel.id(candidate), "task=" + selectedTask.getId());
            rememberPosition();
            return true;
        }

        private void rememberPosition() {
            seenX = target.posX;
            seenY = target.boundingBox.minY;
            seenZ = target.posZ;
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
            if (visible) rememberPosition();
            if (maid.getTask() == MaidTask.RANGED_ATTACK) bow.observe(target, visible, decisionTick);
            return visible;
        }

        @Override
        public double distanceSq() {
            return maid.getDistanceSq(target.posX, target.boundingBox.minY, target.posZ);
        }

        @Override
        public double reachSq() {
            return maid.getTask() == MaidTask.RANGED_ATTACK ? MaidBowAttack.RANGE_SQ
                : MaidCombatTargeting.meleeReachSq(maid, target);
        }

        @Override
        public void claimControl() {
            // 旧路径必须先取消，之后才能由近战提交新路径，避免相同路径对象被误释放
            follow.cancel();
            life.cancel();
        }

        @Override
        public void lookAtTarget() {
            maid.getLookHelper()
                .setLookPosition(seenX, seenY + target.getEyeHeight(), seenZ, 30, maid.getVerticalFaceSpeed());
        }

        @Override
        public boolean hasPath() {
            return ownedPath != null && maid.getNavigator()
                .getPath() == ownedPath
                && !maid.getNavigator()
                    .noPath();
        }

        @Override
        public PathResult approachTarget() {
            if (!targetValid()) return PathResult.UNREACHABLE;
            double dx = seenX - maid.posX, dy = seenY - maid.posY, dz = seenZ - maid.posZ;
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
            if (ownedPath != null && maid.getNavigator()
                .getPath() == ownedPath) maid.getNavigator()
                    .clearPathEntity();
            ownedPath = null;
        }

        @Override
        public boolean attack() {
            if (!targetValid()) return false;
            if (maid.getTask() == MaidTask.RANGED_ATTACK) return bow.fire(target);
            maid.swingItem();
            return maid.attackEntityAsMob(target);
        }

        @Override
        public boolean prepareAttack() {
            return maid.getTask() != MaidTask.RANGED_ATTACK || bow.prepare();
        }

        @Override
        public void interruptAttack() {
            bow.interrupt();
        }

        @Override
        public int attackInterval() {
            return maid.getTask() == MaidTask.RANGED_ATTACK ? 2 : MaidCombatController.ATTACK_INTERVAL;
        }

        @Override
        public void release(boolean suppressTarget) {
            if (target != null) {
                if (suppressTarget) {
                    ignoredTarget = target.getUniqueID();
                    ignoreUntil = decisionTick + 100;
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

        @Override
        public Status stopReason() {
            if (maid.worldObj.isRemote) return Status.CLIENT;
            if (!maid.isEntityAlive()) return Status.DEAD;
            if (suspended) return Status.SUSPENDED;
            if (maid.ridingEntity != null) return Status.RIDING;
            if (maid.isSitting()) return Status.SITTING;
            if (maid.hasPose(MaidPose.SLEEPING)) return Status.SLEEPING;
            if (maid.getLeashed()) return Status.LEASHED;
            if (!maid.isTamed()) return Status.UNTAMED;
            if (maid.getAttackTarget() != null) return Status.BUSY;
            return null;
        }

        @Override
        public boolean refreshOwner() {
            owner = null;
            int inspected = 0;
            for (Object value : maid.worldObj.playerEntities) {
                inspected++;
                if (value instanceof EntityPlayer player && maid.isOwner(player) && valid(player)) {
                    owner = player;
                    break;
                }
            }
            telemetry.scan("follow_owner", maid.worldObj.playerEntities.size(), inspected, "world_player_list");
            return owner != null;
        }

        @Override
        public void forgetOwner() {
            owner = null;
        }

        private boolean valid(EntityPlayer player) {
            return player != null && !(player instanceof FakePlayer)
                && player.isEntityAlive()
                && !player.isDead
                && player.worldObj == maid.worldObj
                && player.dimension == maid.dimension
                && (!(player instanceof EntityPlayerMP serverPlayer) || serverPlayer.theItemInWorldManager.getGameType()
                    .getID() != 3);
        }

        @Override
        public boolean ownerValid() {
            return valid(owner) && maid.isOwner(owner) && maid.worldObj.playerEntities.contains(owner);
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
            if (!ownerValid()) return PathResult.UNREACHABLE;
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
            if (current != null && current == ownedPath) {
                maid.getNavigator()
                    .clearPathEntity();
            }
            // 原版移动和注视请求每tick消费；取消后不再提交，也不覆盖其他动作的新请求
            ownedPath = null;
        }
    }
}
