package com.tianluoqaq.tlmnh.common.ai.aquatic;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.faithful.locomotion.MaidSwimJumpActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.locomotion.MaidSwimmingActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 水中物理与泳姿生命周期；导航决策由原版管理器维护 */
public final class MaidAquaticController implements MaidSwimmingActor, MaidSwimJumpActor {

    private final EntityMaid maid;
    private final MaidSwimManager manager;
    private final MaidSwimJumpTask jump = new MaidSwimJumpTask(
        AquaticAiTuning.AQUATIC_CONTROLLER_MAID_SWIM_JUMP_TASK_FIRST_FACTOR);
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
        var brain = maid.getBrainCoordinator();
        var policy = brain.peekPolicyRuntime();
        boolean prototype = policy == null
            || policy.isReady() && policy.getProfile() == MaidBrainProfile.LEGACY_PROTOTYPE;
        if (prototype) manager.setGoingToBreath(brain.hasLegacyBreathActive());
        manager.setEatBreatheItem(
            maid.getMealController()
                .isBreathing());
        manager.updateSwimming();
        if (prototype) jump.tick(this, maid.worldObj.getTotalWorldTime());
    }

    /** Original EntityMaid.aiStep ticks its navigation manager after super.aiStep */
    public void afterLivingUpdate() {
        if (maid.worldObj.isRemote || !maid.isEntityAlive()
            || maid.getBrainCoordinator()
                .isSuspended())
            return;
        if (maid.getNavigator() instanceof MaidPathNavigator navigator) navigator.tickMode();
        // Existing prototype oxygen route owns its separate actuator until the faithful breath task is wired
        var brain = maid.getBrainCoordinator();
        if (brain.hasLegacyBreathSwimming()) {
            manager.setWantToSwim(true);
            var target = brain.peekLegacySwimTarget();
            if (target != null) manager.setSwimTarget(target);
        }
    }

    public boolean travelWater(float strafe, float forward) {
        if (maid.worldObj.isRemote || !inWater()) return false;
        if (!manager.wantToSwim()) {
            MaidWaterPhysics.travel(maid, strafe, forward, manager.isReadyToLand() || underWater());
            return true;
        }
        maid.moveFlying(strafe, forward, AquaticAiTuning.AQUATIC_CONTROLLER_MOVE_FLYING_THIRD_FACTOR);
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        maid.motionX *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
        maid.motionY *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
        maid.motionZ *= AquaticAiTuning.AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR;
        return true;
    }

    public void clear() {
        pendingJump = false;
        if (maid.getNavigator() instanceof MaidPathNavigator navigator) navigator.resetNavigationIfIdle();
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
        return maid.getEyeHeight() < AquaticAiTuning.MIN_GET_EYE_HEIGHT_THRESHOLD
            ? AquaticAiTuning.AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE
            : AquaticAiTuning.MIN_GET_EYE_HEIGHT_THRESHOLD;
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

    public void clearPendingJump() {
        pendingJump = false;
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
        for (int x = MathHelper.floor_double(box.minX + AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); x
            <= MathHelper.floor_double(box.maxX - AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); x++)
            for (int z = MathHelper.floor_double(box.minZ + AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); z
                <= MathHelper.floor_double(box.maxZ - AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); z++)
                for (int y = MathHelper
                    .floor_double(box.minY + AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); y
                        <= MathHelper
                            .floor_double(box.maxY - AquaticAiTuning.AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET); y++) {
                                if (!water(x, y, z)) continue;
                                double surface = water(x, y + 1, z) ? y + 1
                                    : y + 1
                                        - BlockLiquid.getLiquidHeightPercent(maid.worldObj.getBlockMetadata(x, y, z));
                                height = Math.max(height, surface - box.minY);
                            }
        return height;
    }
}
