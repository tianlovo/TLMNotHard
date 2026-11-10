package com.tianluoqaq.tlmnh.compat.endlessids.development.biome;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** Read-only access to the installed EndlessIDs public chunk biome hook for the fixed HQA plains gate. */
public final class EndlessIdsBiomeRead {

    private static final String GUARD = "endlessids/development-biome-read";

    private EndlessIdsBiomeRead() {}

    public static void requireFixedPlains(Object chunk) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD))
            throw new IllegalStateException("Formal EndlessIDs chunk biome hook remains protected");
        short[] biomes;
        try {
            biomes = (short[]) chunk.getClass()
                .getMethod("getBiomeShortArray")
                .invoke(chunk);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            throw new IllegalStateException("Formal EndlessIDs chunk biome hook unavailable", failure);
        }
        if (biomes == null || biomes.length != 256) throw new IllegalStateException("Biome array missing");
        for (short id : biomes)
            if ((id & 65535) != 1) throw new IllegalStateException("Fixed HQA plains biome changed");
    }
}
