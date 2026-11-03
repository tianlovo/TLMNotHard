package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;

/** 原生worldgen不发CheckSpawn；仅在该创建入口提交新女仆前补独立开关和维度门 */
@Mixin(SpawnerAnimals.class)
public abstract class MixinMaidWorldGenSpawning {

    @Redirect(
        method = "performWorldGenSpawning(Lnet/minecraft/world/World;Lnet/minecraft/world/biome/BiomeGenBase;IIIILjava/util/Random;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z"),
        require = 1,
        allow = 1)
    private static boolean tlmnh$naturalMaidWorldGen(World world, Entity entity) {
        if (entity instanceof EntityMaid && (!MaidAcquisitionConfig.naturalSpawning
            || !MaidAcquisitionConfig.allowsDimension(world.provider.dimensionId))) return false;
        return world.spawnEntityInWorld(entity);
    }
}
