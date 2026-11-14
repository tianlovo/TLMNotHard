package com.tianluoqaq.tlmnh.compat.botania.development.shedding.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tianluoqaq.tlmnh.qa.BotaniaSheddingProtocol;

@Mixin(value = Entity.class, priority = 500)
public abstract class NativeSheddingSpawnMixin {

    @WrapOperation(
        method = { "entityDropItem(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;",
            "func_70099_a(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;" },
        at = {
            @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/World;spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_72838_d(Lnet/minecraft/entity/Entity;)Z") },
        remap = false,
        require = 1,
        allow = 1)
    private boolean shedding$spawn(World world, Entity item, Operation<Boolean> original) {
        Object token = BotaniaSheddingProtocol.beforeSpawn((Entity) (Object) this, world, item);
        boolean returned = false;
        boolean result = false;
        try {
            result = original.call(world, item);
            returned = true;
            return result;
        } finally {
            BotaniaSheddingProtocol.afterSpawn(token, returned, result);
        }
    }
}
