package com.tianluoqaq.tlmnh.common.ai.policy;

/** 研究模式先明确可用性，不以旧原型回退冒充忠实或增强实现 */
public enum MaidBrainProfile {

    LEGACY_PROTOTYPE("legacy_prototype", true),
    FOLLOW_CORE_COMPONENT("follow_core_component", true, true),
    FAITHFUL_TLM("faithful_tlm", true, true),
    ENHANCED_CORE_COMPONENT("enhanced_core_component", true, true),
    ENHANCED("enhanced", false);

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
}
