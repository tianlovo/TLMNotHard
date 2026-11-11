package com.tianluoqaq.tlmnh.common.ai.telemetry;

/** AI 运行期遥测与采样参数（Diagnostics） */
public final class AiTelemetryDiagnostics {

    public static final String MSG_NEGATIVE_TELEMETRY_STAMP = "Negative telemetry stamp";
    public static final String MSG_NOT_NAVIGATION_PHASE = "Not a navigation phase";
    public static final String MSG_NAVIGATION_DURATION_BELONGS_TO_RETURNED = "Navigation duration belongs to RETURNED only";
    public static final String MSG_INVALID_SCAN_COUNTS = "Invalid scan counts";
    public static final String MSG_TERMINAL_ACTION_ID_REQUIRED = "Terminal action ID required";
    public static final String MSG_COMPLETION_NEEDS_EXPLICIT_EFFECT_CONFIRMATION = "Completion needs explicit effect confirmation";
    public static final String MSG_NEGATIVE_WORK_DURATION = "Negative work duration";
    public static final String MSG_TELEMETRY_IDENTIFIER_EXCEEDS_160_CHARACTERS = "Telemetry identifier exceeds 160 characters";
    public static final String MSG_NON_FINITE_TELEMETRY_VALUE = "Non-finite telemetry value";
    public static final String MSG_INVALID_TELEMETRY_CAPACITIES = "Invalid telemetry capacities";
    public static final String MSG_RECORDING_EPOCH_MUST_INCREASE = "Recording epoch must increase";
    public static final String MSG_INVALID_TELEMETRY_SESSION_METADATA = "Invalid telemetry session metadata";
    public static final String MSG_TELEMETRY_SCALAR_OVERFLOW = "Telemetry scalar overflow";

    private AiTelemetryDiagnostics() {}
}
