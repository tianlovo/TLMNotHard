package com.tianluoqaq.tlmnh.common.ai.aquatic;

import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 固定现代 TLM 非主动游泳 travel 的三轴输入与 LivingEntity 水中执行 */
public final class MaidWaterPhysics {

    private MaidWaterPhysics() {}

    public static void travel(EntityMaid maid, float strafe, float forward, boolean lift) {
        double oldY = maid.posY;
        boolean falling = maid.motionY <= 0;
        var input = acceleration(strafe, forward, lift, maid.rotationYaw);
        maid.motionX += input.x;
        maid.motionY += input.y;
        maid.motionZ += input.z;
        maid.moveEntity(maid.motionX, maid.motionY, maid.motionZ);
        if (maid.isCollidedHorizontally && maid.isOnLadder()) maid.motionY = AquaticAiTuning.WATER_PHYSICS_MOTION_Y;
        double horizontalDrag = maid.isSprinting() ? AquaticAiTuning.WATER_PHYSICS_TRAVEL_FACTOR
            : AquaticAiTuning.AQUATIC_CONTROLLER_MAID_SWIM_JUMP_TASK_FIRST_FACTOR;
        maid.motionX *= horizontalDrag;
        maid.motionY *= AquaticAiTuning.AQUATIC_CONTROLLER_MAID_SWIM_JUMP_TASK_FIRST_FACTOR;
        maid.motionZ *= horizontalDrag;
        maid.motionY = fallingVelocity(
            maid.motionY,
            AquaticAiTuning.WATER_PHYSICS_FALLING_VELOCITY_SECOND_FACTOR,
            falling,
            maid.isSprinting());
        if (maid.isCollidedHorizontally && maid.isOffsetPositionInLiquid(
            maid.motionX,
            maid.motionY + AquaticAiTuning.WATER_PHYSICS_IS_OFFSET_POSITION_IN_LIQUID_SECOND_FA - maid.posY + oldY,
            maid.motionZ)) maid.motionY = AquaticAiTuning.WATER_PHYSICS_MOTION_Y_2;
    }

    /** TLM travel.scale(1.2).add(0, lift ? 0.5 : 0.05, 0)，再以现代 moveRelative 归一化 */
    public static Acceleration acceleration(float strafe, float forward, boolean lift, float yaw) {
        double x = strafe * AquaticAiTuning.WATER_PHYSICS_ACCELERATION_X_OFFSET,
            y = lift ? AquaticAiTuning.BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR
                : AquaticAiTuning.WATER_PHYSICS_ACCELERATION_FACTOR,
            z = forward * AquaticAiTuning.WATER_PHYSICS_ACCELERATION_X_OFFSET;
        double squared = x * x + y * y + z * z;
        double scale = (double) AquaticAiTuning.WATER_PHYSICS_ACCELERATION_FACTOR_2
            / Math.max(AquaticAiTuning.BREATH_CONTROLLER_DEFAULT_UNIT_STEP, Math.sqrt(squared));
        float sin = MathHelper.sin(yaw * ((float) Math.PI / AquaticAiTuning.HALF_TURN_DEGREES_F));
        float cos = MathHelper.cos(yaw * ((float) Math.PI / AquaticAiTuning.HALF_TURN_DEGREES_F));
        return new Acceleration((x * cos - z * sin) * scale, y * scale, (z * cos + x * sin) * scale);
    }

    /** 原生现代 getFluidFallingAdjustedMovement，默认重力0.08/16；奔跑分支不再减重力 */
    public static double fallingVelocity(double afterDrag, double gravity, boolean falling, boolean sprinting) {
        if (sprinting) return afterDrag;
        return falling
            && Math.abs(afterDrag - AquaticAiTuning.BREATH_CONTROLLER_TRAVEL_WATER_FACTOR)
                >= AquaticAiTuning.WATER_PHYSICS_FALLING_VELOCITY_FACTOR
            && Math.abs(afterDrag - gravity / AquaticAiTuning.WATER_PHYSICS_ABS_FIRST_PARAM)
                < AquaticAiTuning.WATER_PHYSICS_FALLING_VELOCITY_FACTOR
                    ? AquaticAiTuning.WATER_PHYSICS_FALLING_VELOCITY_FACTOR_2
                    : afterDrag - gravity / AquaticAiTuning.WATER_PHYSICS_ABS_FIRST_PARAM;
    }

    public static final class Acceleration {

        public final double x, y, z;

        private Acceleration(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
