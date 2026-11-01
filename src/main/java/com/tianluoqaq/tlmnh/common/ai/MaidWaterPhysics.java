package com.tianluoqaq.tlmnh.common.ai;

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
        if (maid.isCollidedHorizontally && maid.isOnLadder()) maid.motionY = .2;
        double horizontalDrag = maid.isSprinting() ? .9F : .8F;
        maid.motionX *= horizontalDrag;
        maid.motionY *= .8F;
        maid.motionZ *= horizontalDrag;
        maid.motionY = fallingVelocity(maid.motionY, .08, falling, maid.isSprinting());
        if (maid.isCollidedHorizontally && maid
            .isOffsetPositionInLiquid(maid.motionX, maid.motionY + .6000000238418579 - maid.posY + oldY, maid.motionZ))
            maid.motionY = .30000001192092896;
    }

    /** TLM travel.scale(1.2).add(0, lift ? 0.5 : 0.05, 0)，再以现代 moveRelative 归一化 */
    public static Acceleration acceleration(float strafe, float forward, boolean lift, float yaw) {
        double x = strafe * 1.2, y = lift ? .5 : .05, z = forward * 1.2;
        double squared = x * x + y * y + z * z;
        double scale = (double) .02F / Math.max(1, Math.sqrt(squared));
        float sin = MathHelper.sin(yaw * ((float) Math.PI / 180F));
        float cos = MathHelper.cos(yaw * ((float) Math.PI / 180F));
        return new Acceleration((x * cos - z * sin) * scale, y * scale, (z * cos + x * sin) * scale);
    }

    /** 原生现代 getFluidFallingAdjustedMovement，默认重力0.08/16；奔跑分支不再减重力 */
    public static double fallingVelocity(double afterDrag, double gravity, boolean falling, boolean sprinting) {
        if (sprinting) return afterDrag;
        return falling && Math.abs(afterDrag - .005) >= .003 && Math.abs(afterDrag - gravity / 16) < .003 ? -.003
            : afterDrag - gravity / 16;
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
