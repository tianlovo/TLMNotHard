package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.HodgePopulationProbe;

/** Default-off QA-only passive observation. Never cancels or changes a provider argument/result. */
@Pseudo
@Mixin(targets = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler", remap = false)
public abstract class HodgePopulationSchedulerMixin {

    @Inject(method = "processTick", at = @At("HEAD"), require = 1, remap = false)
    private void tlmnhqa$head(WorldServer w, ChunkProviderServer c, int range, AnvilChunkLoader loader,
        @Coerce Object callback, CallbackInfo ci) {
        HodgePopulationProbe.head(this, w, c, range);
    }

    @Inject(method = "processTick", at = @At("RETURN"), require = 2, remap = false)
    private void tlmnhqa$returned(WorldServer w, ChunkProviderServer c, int range, AnvilChunkLoader loader,
        @Coerce Object callback, CallbackInfo ci) {
        HodgePopulationProbe.returned(this, w, c, range);
    }

    @Inject(
        method = { "enqueueForPlayer", "removePlayer", "deferChunkPopulation", "trackIfUnpopulated", "onChunkUnload",
            "incrementPopulationDepth", "decrementPopulationDepth" },
        at = @At("HEAD"),
        require = 7,
        remap = false)
    private void tlmnhqa$mutation(CallbackInfo ci) {
        HodgePopulationProbe.mutation(this);
    }

    @Inject(method = "clearDimensionData", at = @At("HEAD"), require = 1, remap = false)
    private static void tlmnhqa$clear(CallbackInfo ci) {
        HodgePopulationProbe.clear();
    }
}
