package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;

/** 服务端启动配置；新实体统一解析，不写每女仆策略NBT，也不开放研究组件 */
public final class MaidBehaviorConfig {

    private static MaidBrainProfile profile = MaidBrainProfile.ENHANCED;
    private static String configuredValue = MaidBrainProfile.ENHANCED.getId();
    private static boolean fallback;

    private MaidBehaviorConfig() {}

    public static void load(Configuration config) {
        // Use the raw property: Configuration.getString(...validValues) can replace an unrecognized value.
        configuredValue = config
            .get(
                MaidConfigConfigSpec.ID_BEHAVIOR,
                MaidConfigConfigSpec.ID_PROFILE,
                MaidBrainProfile.ENHANCED.getId(),
                MaidConfigConfigSpec.MSG_LEGACY_PROTOTYPE_ENHANCED_ENHANCED_SERVER)
            .getString();
        var parsed = MaidBrainProfile.fromProductionId(configuredValue);
        fallback = parsed == null;
        profile = fallback ? MaidBrainProfile.LEGACY_PROTOTYPE : parsed;
        if (fallback)
            ModContext.LOG.warn(MaidConfigConfigSpec.MSG_UNKNOWN_OR_DEVELOPMENT_ONLY_BEHAVIOR, configuredValue);
    }

    public static MaidBrainProfile profile() {
        return profile;
    }

    public static String configuredValue() {
        return configuredValue;
    }

    public static boolean usedFallback() {
        return fallback;
    }
}
