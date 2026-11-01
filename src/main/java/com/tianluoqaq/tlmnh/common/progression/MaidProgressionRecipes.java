package com.tianluoqaq.tlmnh.common.progression;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** 核心进度注册边界，提供方缺失或API失败时停用配方能力并保留现有物品 */
public final class MaidProgressionRecipes {

    private static boolean attempted, registered;

    private MaidProgressionRecipes() {}

    public static void register() {
        if (attempted) throw new IllegalStateException("Maid progression recipes already attempted");
        attempted = true;
        registered = IntegrationGuard.call(Side.SERVER, ExternalFeature.PROGRESSION_RECIPES, () -> {
            if (!Loader.isModLoaded("gregtech") || !Loader.isModLoaded("dreamcraft"))
                throw new IllegalStateException("GregTech and GTNH progression providers are required for recipes");
            GregTechProgression.register();
            return true;
        }, () -> false);
    }

    public static boolean registered() {
        return registered;
    }
}
