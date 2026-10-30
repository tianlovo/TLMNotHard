package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.pathfinding.PathEntity;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 唯一服务端任务入口，首批接入主人跟随和状态中断，战斗及生活行为尚未接入 */
public final class MaidBrainCoordinator extends EntityAIBase {

    private final EntityMaid maid;
    private final MaidFollowController follow;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private boolean suspended;

    public MaidBrainCoordinator(EntityMaid maid) {
        this.maid = maid;
        follow = new MaidFollowController(new EntityActor(), maid.getEntityId());
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
        follow.tick();
    }

    @Override
    public void resetTask() {
        follow.cancel();
    }

    public MaidFollowController getFollowController() {
        return follow;
    }

    public MaidBoundedPathPlanner.Attempt getLastPathAttempt() {
        return planner.getLastAttempt();
    }

    /** 供受控场景暂时让出行为，恢复后重新感知，不持久化为玩家玩法状态 */
    public void setSuspended(boolean suspended) {
        if (maid.worldObj.isRemote) return;
        if (this.suspended != suspended) {
            this.suspended = suspended;
            follow.cancel();
        }
    }

    public boolean isSuspended() {
        return suspended;
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
