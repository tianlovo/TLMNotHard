package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.tianluoqaq.tlmnh.common.entity.MaidPoseStateAccess;

@Mixin(Entity.class)
public interface MixinEntityPose extends MaidPoseStateAccess {

    @Accessor("firstUpdate")
    boolean tlmnh$isFirstUpdate();
}
