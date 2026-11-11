package com.tianluoqaq.tlmnh.common.combat.projectile;

/** 投射物追踪与碰撞参数（Diagnostics） */
public final class ProjectileCombatDiagnostics {

    public static final String MSG_NATIVE_ARROW_IMPACT_INTERCEPTION_UNAVAILABLE = "Native arrow impact interception unavailable; release refused";
    public static final String MSG_ACTUAL_SERVER_PVP_RULE_IS = "Actual server PvP rule is unavailable";
    public static final String MSG_PROTECTED_PROJECTILE_REACHED_THE_DAMAGE = "Protected projectile reached the damage boundary; native impact interception unavailable or impact permission changed";
    public static final String MSG_NONCANONICAL_PROJECTILE_SOURCE_UUID = "Noncanonical projectile source UUID";
    public static final String MSG_UNSUPPORTED_PROJECTILE_SOURCE_RECORD_RETAINED = "Unsupported projectile source record retained unchanged";
    public static final String MSG_NATIVE_PROJECTILE_SOURCE_LIFECYCLE_UNAVAILABLE = "Native projectile source lifecycle unavailable";
    public static final String MSG_PROJECTILE_SOURCE_WORLD_CHANGED_BEFORE = "Projectile source world changed before release";
    public static final String MSG_PROJECTILE_IMPACT_CAPABILITY_UNAVAILABLE_ORIGINA = "Projectile impact capability unavailable; original entity retained";
    public static final String MSG_PROJECTILE_REMAINS_STOPPED_AFTER_RELOAD = "Projectile remains stopped after reload";
    public static final String MSG_PROJECTILE_SOURCE_DIMENSION_CHANGED = "Projectile source dimension changed";
    public static final String MSG_PROJECTILE_SOURCE_REPLACED_BY_AN = "Projectile source replaced by an unverified shooter: ";
    public static final String MSG_EXACT_LIVE_PROJECTILE_SOURCE_CANNOT = "Exact live projectile source cannot be verified";
    public static final String MSG_UNVERIFIED_PLAYER_PROJECTILE_TAKEOVER_CANNOT = "Unverified player projectile takeover cannot lose its transient identity on save";
    public static final String MSG_UNSUPPORTED_PROJECTILE_HANDOFF_RETAINED_UNCHANGE = "Unsupported projectile handoff retained unchanged";
    public static final String MSG_DUPLICATE_LOADED_PROJECTILE_SOURCE_UUID = "Duplicate loaded projectile source UUID";

    private ProjectileCombatDiagnostics() {}
}
