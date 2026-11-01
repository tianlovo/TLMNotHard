package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 女仆各姿态使用现代完整内缩身体，旧版上下各减0.4还会漏掉站姿脚边浅水 */
@Mixin(Entity.class)
public abstract class MixinEntityAquatic {

    @ModifyArg(
        method = "handleWaterMovement",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;handleMaterialAcceleration(Lnet/minecraft/util/AxisAlignedBB;Lnet/minecraft/block/material/Material;Lnet/minecraft/entity/Entity;)Z"),
        index = 0,
        require = 1,
        allow = 1)
    private AxisAlignedBB tlmnh$smallPoseWaterBounds(AxisAlignedBB original) {
        return (Object) this instanceof EntityMaid maid ? maid.boundingBox.contract(.001, .001, .001) : original;
    }
}
