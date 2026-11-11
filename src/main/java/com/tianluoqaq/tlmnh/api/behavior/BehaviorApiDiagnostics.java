package com.tianluoqaq.tlmnh.api.behavior;

/** 行为扩展公共接口契约常量（Diagnostics） */
public final class BehaviorApiDiagnostics {

    public static final String MSG_INVALID_BEHAVIOR_ID = "Invalid behavior id: ";
    public static final String MSG_INVALID_BEHAVIOR_NAMESPACE = "Invalid behavior namespace: ";
    public static final String MSG_INVALID_BEHAVIOR_NAMESPACE_CHARACTER_IN = "Invalid behavior namespace character in: ";
    public static final String MSG_INVALID_BEHAVIOR_PATH_LENGTH = "Invalid behavior path length: ";
    public static final String MSG_INVALID_BEHAVIOR_PATH_SLASH_BOUNDARY = "Invalid behavior path slash boundary: ";
    public static final String MSG_EMPTY_BEHAVIOR_PATH_SEGMENT = "Empty behavior path segment: ";
    public static final String MSG_RELATIVE_DOT_SEGMENT_IS_NOT = "Relative dot segment is not allowed in behavior path: ";
    public static final String MSG_INVALID_BEHAVIOR_PATH_CHARACTER_IN = "Invalid behavior path character in: ";
    public static final String MSG_MAIDBEHAVIORREGISTRY_IS_ALREADY_FROZEN = "MaidBehaviorRegistry is already frozen: ";
    public static final String MSG_EXTERNAL_PROVIDER_CANNOT_USE_BUILT = "External provider cannot use built-in namespace: ";
    public static final String MSG_DUPLICATE_BEHAVIOR_PROVIDER_ID = "Duplicate behavior provider id: ";
    public static final String COMMA_SPACE_DELIMITER = ", ";

    private BehaviorApiDiagnostics() {}
}
