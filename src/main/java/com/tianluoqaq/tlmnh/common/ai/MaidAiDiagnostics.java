package com.tianluoqaq.tlmnh.common.ai;

/** 女仆核心 AI 调度与恐慌控制参数（Diagnostics） */
public final class MaidAiDiagnostics {

    public static final String MSG_REQUESTED_BRAIN_BINDING_FAILED = "Requested Brain binding failed";
    public static final String MSG_DEVELOPMENT_COMPONENT_CANNOT_BE_INSTALLED = "Development component cannot be installed at this lifecycle boundary";
    public static final String MSG_NO_DEVELOPMENT_POLICY_RUNTIME = "No development policy runtime";
    public static final String MSG_DEVELOPMENT_COMPONENT_SELECTION_FAILED = "Development component selection failed";
    public static final String MSG_PERSISTENT_READ_DURING_POLICY_CLEANUP = "Persistent read during policy cleanup or creation";
    public static final String MSG_UNBALANCED_PERSISTENT_READ_BOUNDARY = "Unbalanced persistent read boundary";
    public static final String MSG_BEHAVIOR_EXTENSION_SAVE_REJECTED_FOR = "Behavior extension save rejected for maid {}: stale snapshot or invalid tag type";
    public static final String MSG_MAID_BRAIN_LIFECYCLE_ALREADY_REGISTERED = "Maid brain lifecycle already registered";
    public static final String MSG_MAID_POLICY_LIFECYCLE_FAILED_ACTOR = "Maid policy lifecycle failed; actor remains blocked: operation={} maid={}";

    private MaidAiDiagnostics() {}
}
