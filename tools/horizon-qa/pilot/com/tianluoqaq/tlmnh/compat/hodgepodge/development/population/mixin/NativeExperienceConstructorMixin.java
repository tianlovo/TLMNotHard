package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin;

import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.qa.DeathProductsProtocol;

/** Captures the actual native constructor arguments without changing them. */
@Mixin(value = EntityXPOrb.class, priority = 500)
public abstract class NativeExperienceConstructorMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/World;DDDI)V", at = @At("RETURN"), remap = false, require = 1)
    private void deathProducts$constructed(World world, double x, double y, double z, int amount, CallbackInfo ci) {
        DeathProductsProtocol.constructed((EntityXPOrb) (Object) this, world, x, y, z, amount);
    }
}
