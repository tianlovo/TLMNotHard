package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.tianluoqaq.tlmnh.qa.DeathProductsProtocol;

/** Passive receiver/return observation. Native execution is outside the paid observer closure. */
@Mixin(value = EntityLivingBase.class, priority = 500)
public abstract class NativeDeathUpdateMixin {

    @WrapMethod(method = { "onDeathUpdate", "func_70609_aI" }, remap = false, require = 1, allow = 1)
    private void deathProducts$update(Operation<Void> original) {
        Object token = DeathProductsProtocol.beginUpdate((EntityLivingBase) (Object) this);
        boolean returned = false;
        try {
            original.call();
            returned = true;
        } finally {
            DeathProductsProtocol.endUpdate(token, returned);
        }
    }

    @Inject(
        method = { "onDeathUpdate", "func_70609_aI" },
        at = { @At(
            value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/entity/EntityLivingBase;getExperiencePoints(Lnet/minecraft/entity/player/EntityPlayer;)I"),
            @At(
                value = "INVOKE_ASSIGN",
                target = "Lnet/minecraft/entity/EntityLivingBase;func_70693_a(Lnet/minecraft/entity/player/EntityPlayer;)I") },
        remap = false,
        require = 1,
        allow = 1)
    private void deathProducts$lateAmount(CallbackInfo ci, @Local(index = 1) int residual) {
        DeathProductsProtocol.lateAmount((EntityLivingBase) (Object) this, residual);
    }
}
