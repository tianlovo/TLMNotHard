package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathNavigate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.common.ai.faithful.NativeNavigationStopSource;
import com.tianluoqaq.tlmnh.common.ai.faithful.NativeNavigationStopState;

/** 观察原生 watchdog 的唯一清空调用，不替换或重发原调用 */
@Mixin(PathNavigate.class)
public abstract class MixinPathNavigate implements NativeNavigationStopSource {

    @Shadow
    private EntityLiving theEntity;

    @Unique
    private NativeNavigationStopState tlmnh$stopState;

    @Override
    public NativeNavigationStopState tlmnh$navigationStopState() {
        if (tlmnh$stopState == null) tlmnh$stopState = new NativeNavigationStopState();
        return tlmnh$stopState;
    }

    @Inject(
        method = "pathFollow",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/pathfinding/PathNavigate;clearPathEntity()V"),
        require = 1,
        allow = 1)
    private void tlmnh$beforeWatchdogClear(CallbackInfo ci) {
        if (tlmnh$stopState == null || !tlmnh$stopState.isObserving()) return;
        var world = theEntity.worldObj;
        tlmnh$stopState.beforeWatchdogClear(
            ((PathNavigate) (Object) this).getPath(),
            world,
            world.getTotalWorldTime(),
            theEntity.ticksExisted);
    }

    @Inject(
        method = "pathFollow",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/pathfinding/PathNavigate;clearPathEntity()V",
            shift = At.Shift.AFTER),
        require = 1,
        allow = 1)
    private void tlmnh$afterWatchdogClear(CallbackInfo ci) {
        if (tlmnh$stopState == null) return;
        var world = theEntity.worldObj;
        tlmnh$stopState.afterWatchdogClear(
            ((PathNavigate) (Object) this).getPath(),
            world,
            world.getTotalWorldTime(),
            theEntity.ticksExisted);
    }
}
