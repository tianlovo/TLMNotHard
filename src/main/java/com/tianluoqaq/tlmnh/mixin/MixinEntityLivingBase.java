package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 现代水中微小加速度不能被旧版 0.005 归零；仅适配正在执行呼吸路线的女仆 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase {

    @ModifyConstant(method = "onLivingUpdate", constant = @Constant(doubleValue = 0.005D), require = 3, allow = 3)
    private double tlmnh$swimmingMotionThreshold(double original) {
        return (Object) this instanceof EntityMaid maid && maid.isInWater()
            && maid.getBrainCoordinator()
                .getBreathController()
                .isSwimming() ? .003D : original;
    }
}
