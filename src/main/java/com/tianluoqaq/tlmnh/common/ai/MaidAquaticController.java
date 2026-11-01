package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimJumpActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimmingActor;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 旧导航的水中执行投影；完整现代 Amphibious 节点器仍须独立移植和对照 */
public final class MaidAquaticController implements MaidSwimmingActor, MaidSwimJumpActor {

    private final EntityMaid maid;
    private final MaidSwimManager manager;
    private final MaidSwimJumpTask jump = new MaidSwimJumpTask(.8F);
    private boolean waterMode;
    private float beforeYaw;
    private boolean pendingJump;

    public MaidAquaticController(EntityMaid maid) {
        this.maid = maid;
        manager = new MaidSwimManager(this);
    }

    public MaidSwimManager getManager() {
        return manager;
    }

    public void beforeLivingUpdate() {
        if (maid.worldObj.isRemote) return;
        beforeYaw = maid.rotationYaw;
        if (!maid.isEntityAlive() || maid.getBrainCoordinator()
            .isSuspended()) {
            clear();
            return;
        }
        var breath = maid.getBrainCoordinator()
            .getBreathController();
        manager.setGoingToBreath(breath.isActive());
        manager.setEatBreatheItem(
            maid.getMealController()
                .isBreathing());
        var path = maid.getNavigator()
            .getPath();
        var end = path == null ? null : path.getFinalPathPoint();
        boolean submergedGoal = end != null && water(end.xCoord, end.yCoord, end.zCoord)
            && water(end.xCoord, end.yCoord + 1, end.zCoord)
            && water(end.xCoord, end.yCoord + 2, end.zCoord);
        int x = MathHelper.floor_double(maid.posX), y = MathHelper.floor_double(maid.boundingBox.minY),
            z = MathHelper.floor_double(maid.posZ);
        boolean stuck = underWater() && water(x, y, z)
            && loaded(x, y + 1, z)
            && (maid.worldObj.getBlock(x, y + 1, z)
                .getMaterial()
                .isLiquid()
                || !maid.worldObj.getBlock(x, y + 1, z)
                    .getBlocksMovement(maid.worldObj, x, y + 1, z));
        // 原版无当前路径的切换分支不写入WATER模式；不能让闲置落地女仆永远关闭上浮物理
        boolean hasPath = path != null && !path.isFinished();
        if (!waterMode && inWater()
            && (breath.isSwimming() || hasPath && (waterNodes(path, 5) || stuck || submergedGoal))) waterMode = true;
        boolean want = manager.wantToSwim();
        boolean ready = false;
        if (waterMode) {
            if (!inWater()) {
                waterMode = false;
                want = false;
            } else if (stuck || submergedGoal || breath.isSwimming()) want = true;
            else if (end == null && breath.isActive()) want = false;
            else if (end != null && !waterNodes(path, 2)) {
                if (!water(end.xCoord, end.yCoord, end.zCoord) && !water(end.xCoord, end.yCoord - 1, end.zCoord)) {
                    waterMode = false;
                    want = false;
                    ready = true;
                } else want = !surface(end.xCoord, end.yCoord, end.zCoord);
            } else if (end != null) want = underWater();
        }
        manager.setWantToSwim(waterMode && want);
        manager.setReadyToLand(ready);
        var airTarget = breath.nextSwimTarget();
        manager.setSwimTarget(
            airTarget != null ? airTarget
                : end == null ? null : new MaidSwimManager.Target(end.xCoord, end.yCoord, end.zCoord));
        manager.updateSwimming();
        jump.tick(this, maid.worldObj.getTotalWorldTime());
    }

    public boolean travelWater(float strafe, float forward) {
        if (maid.worldObj.isRemote || !inWater()) return false;
        if (!manager.wantToSwim()) {
            MaidWaterPhysics.travel(maid, strafe, forward, manager.isReadyToLand() || underWater());
            return true;
        }
        var path = maid.getNavigator()
            .getPath();
        if (path != null && !path.isFinished()) {
            var point = path.getPosition(maid);
            double x = point.xCoord - maid.posX, y = point.yCoord - maid.boundingBox.minY, z = point.zCoord - maid.posZ;
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length > 1.0e-5) {
                float targetYaw = (float) Math.toDegrees(Math.atan2(z, x)) - 90;
                maid.rotationYaw = beforeYaw
                    + Math.max(-90, Math.min(90, MathHelper.wrapAngleTo180_float(targetYaw - beforeYaw)));
                maid.renderYawOffset = maid.rotationYaw;
                float speed = maid.getAIMoveSpeed();
                maid.motionX += speed * x * .005;
                maid.motionY += speed * y / length * .25;
                maid.motionZ += speed * z * .005;
                forward *= 3;
            }
        } else if (underWater()) {
            maid.setAIMoveSpeed(0);
            forward = 0;
        }
        maid.moveFlying(strafe, forward, .01F);
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        maid.motionX *= .9;
        maid.motionY *= .9;
        maid.motionZ *= .9;
        return true;
    }

    public void clear() {
        pendingJump = false;
        waterMode = false;
        manager.setWantToSwim(false);
        manager.setReadyToLand(false);
        manager.setGoingToBreath(false);
        manager.setEatBreatheItem(false);
        manager.setSwimTarget(null);
        jump.cancel();
        manager.updateSwimming();
    }

    private boolean loaded(int x, int y, int z) {
        return maid.worldObj.blockExists(x, y, z);
    }

    private boolean water(int x, int y, int z) {
        return loaded(x, y, z) && maid.worldObj.getBlock(x, y, z)
            .getMaterial() == Material.water;
    }

    private boolean air(int x, int y, int z) {
        return loaded(x, y, z) && maid.worldObj.isAirBlock(x, y, z);
    }

    private boolean surface(int x, int y, int z) {
        return water(x, y, z) && air(x, y + 1, z) || water(x, y - 1, z) && air(x, y, z)
            || water(x, y + 1, z) && air(x, y + 2, z);
    }

    private boolean waterNodes(PathEntity path, int count) {
        if (path == null || path.isFinished() || path.getCurrentPathIndex() > path.getCurrentPathLength() - count)
            return false;
        for (int i = path.getCurrentPathIndex(); i < path.getCurrentPathIndex() + count; i++) {
            var node = path.getPathPointFromIndex(i);
            if (!water(node.xCoord, node.yCoord, node.zCoord)) return false;
        }
        return true;
    }

    @Override
    public void configureWaterPath() {
        maid.getNavigator()
            .setAvoidsWater(false);
    }

    @Override
    public boolean isClient() {
        return maid.worldObj.isRemote;
    }

    @Override
    public boolean onGround() {
        return maid.onGround;
    }

    @Override
    public boolean isSitting() {
        return maid.isSitting();
    }

    @Override
    public boolean isPassenger() {
        return maid.ridingEntity != null;
    }

    @Override
    public boolean isSleeping() {
        return maid.hasPose(MaidPose.SLEEPING);
    }

    @Override
    public void setSwimming(boolean value) {
        maid.setPose(MaidPose.SWIMMING, value);
    }

    @Override
    public void setSwimmingPose() {
        maid.setEntityPose(MaidEntityPose.SWIMMING);
    }

    @Override
    public void setStandingPose() {
        maid.setEntityPose(MaidEntityPose.STANDING);
    }

    @Override
    public boolean inWater() {
        return maid.isInWater();
    }

    @Override
    public boolean inLava() {
        return maid.handleLavaMovement();
    }

    @Override
    public boolean underWater() {
        return maid.isInsideOfMaterial(Material.water);
    }

    @Override
    public boolean navigationDone() {
        return maid.getNavigator()
            .noPath();
    }

    @Override
    public double fluidJumpThreshold() {
        return maid.getEyeHeight() < .4 ? 0 : .4;
    }

    @Override
    public int nextWorldInt(int bound) {
        return maid.worldObj.rand.nextInt(bound);
    }

    @Override
    public float nextEntityFloat() {
        return maid.getRNG()
            .nextFloat();
    }

    @Override
    public void requestJump() {
        pendingJump = true;
    }

    public void afterNativeAiTick() {
        if (pendingJump && maid.isEntityAlive()
            && !maid.getBrainCoordinator()
                .isSuspended())
            maid.getJumpHelper()
                .setJumping();
        pendingJump = false;
    }

    @Override
    public double waterHeight() {
        double height = 0;
        var box = maid.boundingBox;
        for (int x = MathHelper.floor_double(box.minX + .001); x <= MathHelper.floor_double(box.maxX - .001); x++)
            for (int z = MathHelper.floor_double(box.minZ + .001); z <= MathHelper.floor_double(box.maxZ - .001); z++)
                for (int y = MathHelper.floor_double(box.minY + .001); y
                    <= MathHelper.floor_double(box.maxY - .001); y++) {
                        if (!water(x, y, z)) continue;
                        double surface = water(x, y + 1, z) ? y + 1
                            : y + 1 - BlockLiquid.getLiquidHeightPercent(maid.worldObj.getBlockMetadata(x, y, z));
                        height = Math.max(height, surface - box.minY);
                    }
        return height;
    }
}
