package com.tianluoqaq.tlmnh.common.ai.policy;

/** 研究模式先明确可用性，不以旧原型回退冒充忠实或增强实现 */
public enum MaidBrainProfile {

    LEGACY_PROTOTYPE("legacy_prototype", true),
    FAITHFUL_TLM("faithful_tlm", false),
    ENHANCED("enhanced", false);

    private final String id;
    private final boolean available;

    MaidBrainProfile(String id, boolean available) {
        this.id = id;
        this.available = available;
    }

    public String getId() {
        return id;
    }

    public boolean isAvailable() {
        return available;
    }
}
