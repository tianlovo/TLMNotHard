package com.tianluoqaq.tlmnh.common.ai.policy;

/** 女仆行为意图仲裁策略参数（Diagnostics） */
public final class AiPolicyDiagnostics {

    public static final String MSG_DECISION_FRAME_IS_NO_LONGER = "Decision frame is no longer current";
    public static final String MSG_UTILITY_MUST_BE_FINITE_AND = "Utility must be finite and normalized";
    public static final String MSG_INTENT_IS_NO_LONGER_ACCEPTED = "Intent is no longer accepted by active frame";
    public static final String MSG_INTENT_HAS_NO_TYPED_EXECUTION = "Intent has no typed execution action bound";
    public static final String MSG_INVALID_LIFE_INTENT = "Invalid life intent";
    public static final String MSG_ORDINARY_INTENT_EXECUTION_IS_NOT = "Ordinary intent execution is not installed";

    private AiPolicyDiagnostics() {}
}
