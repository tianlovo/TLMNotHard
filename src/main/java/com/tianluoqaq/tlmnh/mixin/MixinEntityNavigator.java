package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathNavigate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 同时替换原生字段，保证EntityLiving内部直接调用也执行女仆导航 */
@Mixin(EntityLiving.class)
public interface MixinEntityNavigator {

    @Accessor("navigator")
    void tlmnh$setNavigator(PathNavigate navigator);
}
