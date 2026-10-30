package com.tianluoqaq.tlmnh.common.ai;

import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.pathfinding.PathEntity;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
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
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private final MaidBoundedPathPlanner combatPlanner = new MaidBoundedPathPlanner();
    private boolean suspended;
    private long decisionTick;
    private long nextDangerScan;
    private boolean visibleDanger;

    public MaidBrainCoordinator(EntityMaid maid) {
        this.maid = maid;
        follow = new MaidFollowController(new EntityActor(), maid.getEntityId());
        bow = new MaidBowAttack(maid);
        life = new MaidLifeController(maid, follow::cancel);
        combat = new MaidCombatController(new CombatActor(), maid.getEntityId());
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
        decisionTick++;
        boolean fighting = combat.tick();
        boolean living = life.tick(!fighting && !suspended && !hasVisibleDanger());
        if (!fighting && !living) follow.tick();
    }

    @Override
    public void resetTask() {
        combat.cancel();
        life.cancel();
        bow.cancel();
        follow.cancel();
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
            combat.cancel();
            life.cancel();
            bow.cancel();
            follow.cancel();
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
        int inspected = 0;
        for (Object value : maid.worldObj.getEntitiesWithinAABB(IMob.class, maid.boundingBox.expand(8, 4, 8))) {
            if (++inspected > 32) break;
            if (value instanceof EntityLivingBase entity && entity instanceof IMob
                && entity.isEntityAlive()
                && maid.getEntitySenses()
                    .canSee(entity)) {
                visibleDanger = true;
                break;
            }
        }
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
                for (Object value : maid.worldObj.playerEntities) {
                    if (value instanceof EntityPlayer player && MaidCombatTargeting.validOwner(maid, player)) {
                        owner = player;
                        break;
                    }
                }
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
            int inspected = 0;
            for (Object value : maid.worldObj
                .getEntitiesWithinAABB(EntityLivingBase.class, maid.boundingBox.expand(16, 8, 16))) {
                if (++inspected > 64) break;
                if (value instanceof EntityLivingBase candidate && usable(candidate)) {
                    double candidateDistance = maid.getDistanceSqToEntity(candidate);
                    if (candidateDistance < distance) {
                        closest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
            return closest != null && adopt(closest);
        }

        private boolean usable(EntityLivingBase candidate) {
            return candidate != null && !(candidate.getUniqueID()
                .equals(ignoredTarget) && decisionTick < ignoreUntil)
                && MaidCombatTargeting.canAttack(maid, owner, candidate)
                && maid.getEntitySenses()
                    .canSee(candidate);
        }

        private boolean adopt(EntityLivingBase candidate) {
            if (!usable(candidate)) return false;
            target = candidate;
            selectedTask = maid.getTask();
            maid.setAttackTarget(candidate);
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
            for (Object value : maid.worldObj.playerEntities) {
                if (value instanceof EntityPlayer player && maid.isOwner(player) && valid(player)) {
                    owner = player;
                    break;
                }
            }
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
