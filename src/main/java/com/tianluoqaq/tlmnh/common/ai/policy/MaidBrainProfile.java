package com.tianluoqaq.tlmnh.common.ai.policy;

/** 正常配置只开放旧策略和完整N；F参照与窄组件仍限定开发用途 */
public enum MaidBrainProfile {

    LEGACY_PROTOTYPE("legacy_prototype", true),
    FOLLOW_CORE_COMPONENT("follow_core_component", true, true),
    FAITHFUL_TLM("faithful_tlm", true, true),
    ENHANCED_CORE_COMPONENT("enhanced_core_component", true, true),
    ENHANCED("enhanced", true);

    private final String id;
    private final boolean available;
    private final boolean developmentOnly;

    MaidBrainProfile(String id, boolean available) {
        this(id, available, false);
    }

    MaidBrainProfile(String id, boolean available, boolean developmentOnly) {
        this.id = id;
        this.available = available;
        this.developmentOnly = developmentOnly;
    }

    public String getId() {
        return id;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isDevelopmentOnly() {
        return developmentOnly;
    }

    public boolean isEnhanced() {
        return this == ENHANCED_CORE_COMPONENT || this == ENHANCED;
    }

    /** 不将未知配置、参照弹道或窄组件当作正常完整策略；调用者保留原文本并明确回退 */
    public static MaidBrainProfile fromProductionId(String value) {
        if (value == null) return null;
        String id = value.trim()
            .toLowerCase(java.util.Locale.ROOT);
        for (var profile : values())
            if (profile.available && !profile.developmentOnly && profile.id.equals(id)) return profile;
        return null;
    }
}
