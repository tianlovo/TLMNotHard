package com.tianluoqaq.tlmnh.compat.botania.development.shedding.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tianluoqaq.tlmnh.qa.BotaniaSheddingProtocol;

@Pseudo
@Mixin(targets = "vazkii.botania.common.core.handler.SheddingHandler", remap = false, priority = 500)
public abstract class SheddingHandlerMixin {

    @WrapMethod(
        method = "onLivingUpdate(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V",
        remap = false,
        require = 1,
        allow = 1)
    private void shedding$handler(LivingUpdateEvent event, Operation<Void> original) {
        Object token = BotaniaSheddingProtocol.begin(this, event);
        boolean returned = false;
        try {
            original.call(event);
            returned = true;
        } finally {
            BotaniaSheddingProtocol.end(token, returned);
        }
    }

    @WrapOperation(
        method = "onLivingUpdate(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V",
        at = { @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;entityDropItem(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;"),
            @At(
                value = "INVOKE",
                target = "Lnet/minecraft/entity/Entity;func_70099_a(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;") },
        remap = false,
        require = 1,
        allow = 1)
    private EntityItem shedding$drop(Entity actor, ItemStack stack, float offset, Operation<EntityItem> original) {
        Object token = BotaniaSheddingProtocol.drop(actor, stack, offset);
        boolean returned = false;
        EntityItem result = null;
        try {
            result = original.call(actor, stack, offset);
            returned = true;
            return result;
        } finally {
            BotaniaSheddingProtocol.afterDrop(token, returned, result);
        }
    }
}
