package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.mixin.MixinArrowFlightState;

/** 原飞行基线的独立箭类型，保留原生碰撞、拾取与存档，仅在自身更新中调整飞行物理 */
public final class MaidGravityFreeArrow extends EntityArrow {

    public MaidGravityFreeArrow(World world) {
        super(world);
    }

    public MaidGravityFreeArrow(World world, double x, double y, double z) {
        super(world, x, y, z);
    }

    @Override
    public void onUpdate() {
        var flight = (MixinArrowFlightState) (Object) this;
        int airTicks = flight.tlmnh$getAirTicks();
        super.onUpdate();
        // 原生仅在飞行分支增加此值；命中方块的当帧仍执行末尾物理，停留/脱离方块分支不执行
        if (flight.tlmnh$getAirTicks() <= airTicks) return;
        motionY += (double) NativeArrowGravity.forArrow(this);
        if (isInWater()) {
            double scale = (double) .6F / (double) .8F;
            motionX *= scale;
            motionY *= scale;
            motionZ *= scale;
        }
    }

    public void initializeSourceShot(BallisticVector direction, float distance, float charge, int difficulty) {
        setDamage(SourceBowShot.baseDamage(charge, difficulty, rand::nextDouble));
        var velocity = SourceBowShot.velocity(direction, distance, rand::nextDouble);
        motionX = velocity.x;
        motionY = velocity.y;
        motionZ = velocity.z;
        // 目标平台旋转字段用于原生渲染，不改变已按现代Projectile合同生成的速度
        double horizontal = Math.sqrt(motionX * motionX + motionZ * motionZ);
        prevRotationYaw = rotationYaw = (float) (Math.atan2(motionX, motionZ) * 180 / Math.PI);
        prevRotationPitch = rotationPitch = (float) (Math.atan2(motionY, horizontal) * 180 / Math.PI);
    }
}
