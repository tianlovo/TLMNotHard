package com.tianluoqaq.tlmnh.common.combat.ballistics;

/** 弹道预测与箭矢拦截求解参数（Diagnostics） */
public final class BallisticsDiagnostics {

    public static final String MSG_INVALID_ARROW_PHYSICS_PROFILE = "Invalid arrow physics profile";
    public static final String MSG_FLIGHT_TIME_OUTSIDE_THE_SUPPORTED = "Flight time outside the supported budget";
    public static final String MSG_INVALID_MEDIUM_DRAG = "Invalid medium drag";
    public static final String MSG_NON_FINITE_BALLISTIC_VECTOR = "Non-finite ballistic vector";
    public static final String MSG_NATIVE_WATER_FLOW_QUERY_FAILED = "Native water flow query failed";
    public static final String MSG_NATIVE_WATER_FLOW_QUERY_IS = "Native water flow query is unavailable";
    public static final String MSG_UNEXPECTED_NATIVE_WATER_TILE_QUERY = "Unexpected native water tile query";
    public static final String MSG_UNEXPECTED_NATIVE_WATER_LIGHT_QUERY = "Unexpected native water light query";
    public static final String MSG_UNEXPECTED_NATIVE_WATER_POWER_QUERY = "Unexpected native water power query";
    public static final String MSG_UNEXPECTED_NATIVE_WATER_BIOME_QUERY = "Unexpected native water biome query";
    public static final String MSG_UNEXPECTED_NATIVE_WATER_SIDE_QUERY = "Unexpected native water side query";
    public static final String MSG_ARROW_MEDIUM = "Arrow medium: ";
    public static final String MSG_AMBIGUOUS_NATIVE_LIQUID_FLOW_SIGNATURE = "Ambiguous native liquid flow signature";
    public static final String MSG_NATIVE_LIQUID_FLOW_SIGNATURE_UNAVAILABLE = "Native liquid flow signature unavailable";
    public static final String MSG_MISSING_OBSERVATION = "Missing observation";

    private BallisticsDiagnostics() {}
}
