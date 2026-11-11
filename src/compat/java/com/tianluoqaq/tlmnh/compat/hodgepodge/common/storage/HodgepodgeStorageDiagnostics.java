package com.tianluoqaq.tlmnh.compat.hodgepodge.common.storage;

/** Hodgepodge Storage领域常量定义（Diagnostics） */
public final class HodgepodgeStorageDiagnostics {

    public static final String MSG_NATIVE_WORLD_DATA_FLUSH_FAILED = "Native world-data flush failed";
    public static final String MSG_NATIVE_WORLD_DATA_BARRIER_REMAINS = "Native world-data barrier remains unavailable";
    public static final String MSG_UNVERIFIED_NATIVE_WORLD_DATA_SAVER = "Unverified native world-data saver provider";
    public static final String MSG_WORLD_DATA_SAVER_CLASS_DOES = "World-data saver class does not belong to the verified provider";
    public static final String MSG_UNKNOWN_NATIVE_WORLD_DATA_FLUSH = "Unknown native world-data flush API shape";
    public static final String MSG_UNKNOWN_NATIVE_WORLD_DATA_SAVER = "Unknown native world-data saver instance";
    public static final String MSG_CANNOT_COORDINATE_NATIVE_LIFECYCLE_PERSISTENCE = "Cannot coordinate native lifecycle persistence";
    public static final String MSG_UNSUPPORTED_WORLD_DATA_SAVER_JAR = "Unsupported world-data saver JAR CodeSource";
    public static final String MSG_WORLD_DATA_SAVER_CODESOURCE_MUST = "World-data saver CodeSource must resolve to a local file";
    public static final String MSG_WORLD_DATA_SAVER_CODESOURCE_IS = "World-data saver CodeSource is not a regular file";

    private HodgepodgeStorageDiagnostics() {}
}
