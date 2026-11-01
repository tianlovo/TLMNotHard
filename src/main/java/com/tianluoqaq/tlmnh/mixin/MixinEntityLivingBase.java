package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 现代水中微小加速度不能被旧版 0.005 归零；女仆水中主动和非主动移动共用0.003 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase {

    @ModifyConstant(method = "onLivingUpdate", constant = @Constant(doubleValue = 0.005D), require = 3, allow = 3)
    private double tlmnh$swimmingMotionThreshold(double original) {
        return (Object) this instanceof EntityMaid maid && maid.isInWater() ? .003D : original;
    }

    @Redirect(
        method = "moveEntityWithHeading",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;moveEntity(DDD)V", ordinal = 2),
        require = 1,
        allow = 1)
    private void tlmnh$modernClimbingMotion(EntityLivingBase entity, double x, double y, double z) {
        if (entity instanceof EntityMaid maid && maid.usesModernCoreNavigation() && maid.isCanClimb()) {
            double centerX = Math.floor(maid.posX) + .5, centerZ = Math.floor(maid.posZ) + .5;
            if (maid.posX != centerX || maid.posZ != centerZ) maid.setPosition(centerX, maid.posY, centerZ);
            maid.motionX = maid.motionZ = x = z = 0;
        }
        entity.moveEntity(x, y, z);
    }

    @Redirect(
        method = "moveEntityWithHeading",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;isOnLadder()Z", ordinal = 1),
        require = 1,
        allow = 1)
    private boolean tlmnh$modernClimbingWithoutNativeBump(EntityLivingBase entity) {
        return !(entity instanceof EntityMaid maid && maid.usesModernCoreNavigation()) && entity.isOnLadder();
    }
}
