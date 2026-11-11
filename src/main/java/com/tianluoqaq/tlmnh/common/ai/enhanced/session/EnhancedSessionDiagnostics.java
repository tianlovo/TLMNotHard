package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

/** 增强模式会话状态与周期参数（Diagnostics） */
public final class EnhancedSessionDiagnostics {

    public static final String MSG_EXTERNAL_MODULE_METADATA_MISMATCH_FOR = "External module metadata mismatch for provider ";
    public static final String MSG_BEHAVIOR_MODULE_PERSISTENT_READ_PAUSED = "Behavior module {} persistent read paused on maid {}: {}";
    public static final String MSG_EXTERNAL_MAID_BEHAVIOR_MODULE_FAULTED = "External maid behavior module {} faulted during {} on maid {} and was isolated";
    public static final String MSG_OFFER_FRAME_IS_NO_LONGER = "Offer frame is no longer current for ";
    public static final String MSG_CANNOT_ENTER_INACTIVE_PHASE = "Cannot enter INACTIVE phase";
    public static final String MSG_OBSERVATION_VIEW_IS_NOT_ACTIVE = "Observation view is not active for module ";
    public static final String MSG_OFFER_SINK_IS_ONLY_VALID = "Offer sink is only valid during active offer callback for ";
    public static final String MSG_ACTION_AND_HAND_ACCESS_ARE = "Action and hand access are only permitted during active execute callback for ";

    private EnhancedSessionDiagnostics() {}
}
