package com.tianluoqaq.tlmnh.core;

/** CoreMod 早期加载与 Mixin 引导常量（Diagnostics） */
public final class CorePluginDiagnostics {

    public static final String MSG_NATIVE_ARROW_IMPACT_SHAPE_CHANGED = "Native arrow impact shape changed; pre-damage protection remains active";
    public static final String MSG_CANNOT_INSPECT_NATIVE_ARROW_IMPACT = "Cannot inspect native arrow impact hook; using pre-damage protection";
    public static final String MSG_YSM_PASSENGER_MATCHER_DIFFERS_RESOURCE = "YSM passenger matcher differs; resource={} observedSha256={}; original matching remains active";
    public static final String MSG_CANNOT_INSPECT_OPTIONAL_YSM_PASSENGER = "Cannot inspect optional YSM passenger matcher; original matching remains active";
    public static final String MSG_NATIVE_WORLDGEN_SPAWNING_SHAPE_CHANGED = "Native worldgen spawning shape changed; natural maid spawning disabled";
    public static final String MSG_CANNOT_INSPECT_NATIVE_WORLDGEN_SPAWNING = "Cannot inspect native worldgen spawning hook; natural maid spawning disabled";
    public static final String MSG_NATIVE_ARROW_SHIELD_CAPTURE_SHAPE = "Native arrow shield capture shape changed; capture hook disabled";
    public static final String MSG_CANNOT_INSPECT_NATIVE_SHIELD_CAPTURE = "Cannot inspect native shield capture hook; capture disabled";

    private CorePluginDiagnostics() {}
}
