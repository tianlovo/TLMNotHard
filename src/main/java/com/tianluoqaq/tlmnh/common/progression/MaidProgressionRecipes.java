package com.tianluoqaq.tlmnh.common.progression;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** 核心进度注册边界，提供方缺失或API失败时停用配方能力并保留现有物品 */
public final class MaidProgressionRecipes {

    private static boolean attempted, registered;

    private MaidProgressionRecipes() {}

    public static void register() {
        if (attempted)
            throw new IllegalStateException(ProgressionDiagnostics.MSG_MAID_PROGRESSION_RECIPES_ALREADY_ATTEMPTED);
        attempted = true;
        registered = IntegrationGuard.call(Side.SERVER, ExternalFeature.PROGRESSION_RECIPES, () -> {
            if (!Loader.isModLoaded(KnownModIds.GREGTECH) || !Loader.isModLoaded(KnownModIds.DREAMCRAFT))
                throw new IllegalStateException(ProgressionDiagnostics.MSG_GREGTECH_AND_GTNH_PROGRESSION_PROVIDERS);
            GregTechProgression.register();
            return true;
        }, () -> false);
    }

    public static boolean registered() {
        return registered;
    }
}
