package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.projectile.EntityArrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.tianluoqaq.tlmnh.common.combat.ballistics.ArrowFlightStateAccess;

/** 只读原生飞行状态，避免更改其他模组的重力注入或依赖方法局部槽位 */
@Mixin(EntityArrow.class)
public interface MixinArrowFlightState extends ArrowFlightStateAccess {

    @Accessor("ticksInAir")
    int tlmnh$getAirTicks();
}
