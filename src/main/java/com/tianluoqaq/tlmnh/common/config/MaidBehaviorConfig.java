package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;

/** 服务端启动配置；新实体统一解析，不写每女仆策略NBT，也不开放研究组件 */
public final class MaidBehaviorConfig {

    private static MaidBrainProfile profile = MaidBrainProfile.ENHANCED;
    private static String configuredValue = MaidBrainProfile.ENHANCED.getId();
    private static boolean fallback;

    private MaidBehaviorConfig() {}

    public static void load(Configuration config) {
        // Use the raw property: Configuration.getString(...validValues) can replace an unrecognized value.
        configuredValue = config.get(
            "behavior",
            "profile",
            MaidBrainProfile.ENHANCED.getId(),
            "服务器行为策略：legacy_prototype或enhanced；新配置默认enhanced，已有显式配置保留，修改后重启；所有存档按服务器设置加载，无每实体策略迁移 / Server behavior profile: legacy_prototype or enhanced; new configurations default to enhanced; existing explicit values are retained; restart required; applies to existing and new maids")
            .getString();
        var parsed = MaidBrainProfile.fromProductionId(configuredValue);
        fallback = parsed == null;
        profile = fallback ? MaidBrainProfile.LEGACY_PROTOTYPE : parsed;
        if (fallback) TLMNotHard.LOG.warn(
            "Unknown or development-only behavior.profile='{}'; original configuration retained, using legacy_prototype until restart",
            configuredValue);
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
