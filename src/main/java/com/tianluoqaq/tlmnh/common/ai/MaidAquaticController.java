package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimJumpActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimmingActor;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 水中物理与泳姿生命周期；导航决策由原版管理器维护 */
public final class MaidAquaticController implements MaidSwimmingActor, MaidSwimJumpActor {

    private final EntityMaid maid;
    private final MaidSwimManager manager;
    private final MaidSwimJumpTask jump = new MaidSwimJumpTask(.8F);
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
        if (!maid.isEntityAlive() || maid.getBrainCoordinator()
            .isSuspended()) {
            clear();
            return;
        }
        var breath = maid.getBrainCoordinator()
            .getBreathController();
        var policy = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        if (policy == null || !policy.requiresAfterNativeNavigation()) manager.setGoingToBreath(breath.isActive());
        manager.setEatBreatheItem(
            maid.getMealController()
                .isBreathing());
        manager.updateSwimming();
        jump.tick(this, maid.worldObj.getTotalWorldTime());
    }

    /** Original EntityMaid.aiStep ticks its navigation manager after super.aiStep */
    public void afterLivingUpdate() {
        if (maid.worldObj.isRemote || !maid.isEntityAlive()
            || maid.getBrainCoordinator()
                .isSuspended())
            return;
        if (maid.getNavigator() instanceof com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator navigator)
            navigator.tickMode();
        // Existing prototype oxygen route owns its separate actuator until the faithful breath task is wired
        var breath = maid.getBrainCoordinator()
            .getBreathController();
        if (breath.isSwimming()) {
            manager.setWantToSwim(true);
            var target = breath.nextSwimTarget();
            if (target != null) manager.setSwimTarget(target);
        }
    }

    public boolean travelWater(float strafe, float forward) {
        if (maid.worldObj.isRemote || !inWater()) return false;
        if (!manager.wantToSwim()) {
            MaidWaterPhysics.travel(maid, strafe, forward, manager.isReadyToLand() || underWater());
            return true;
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
        if (maid.getNavigator() instanceof com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator navigator)
            navigator.resetNavigationIfIdle();
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
