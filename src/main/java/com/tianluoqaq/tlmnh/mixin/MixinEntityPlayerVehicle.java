package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidPlayerVehicleState;

/** 适配固定原TLM PlayerMixin.removeVehicle的单调3000ms窗口，旧版入口为mountEntity(null) */
@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayerVehicle implements MaidPlayerVehicleState {

    @Unique
    private long tlmnh$lastDismount = -1;

    @Inject(method = "mountEntity", at = @At("HEAD"), require = 1, allow = 1)
    private void tlmnh$rememberDismount(Entity target, CallbackInfo callback) {
        if (((EntityPlayer) (Object) this).ridingEntity != null) tlmnh$lastDismount = System.nanoTime() / 1_000_000L;
    }

    public boolean tlmnh$inDismountCooldown() {
        return tlmnh$lastDismount != -1 && System.nanoTime() / 1_000_000L - tlmnh$lastDismount < 3000;
    }
}
