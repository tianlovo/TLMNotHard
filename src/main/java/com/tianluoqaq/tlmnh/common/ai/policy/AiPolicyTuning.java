package com.tianluoqaq.tlmnh.common.ai.policy;

/** 女仆行为意图仲裁策略参数（Tuning） */
public final class AiPolicyTuning {

    public static final int MINIMUM_HOLD_TICKS = 20;
    public static final double SWITCH_MARGIN = .05;
    public static final int MAXIMUM_AGE = 20;
    public static final int MAX_UTILITY_THRESHOLD = 1;

    private AiPolicyTuning() {}
}
