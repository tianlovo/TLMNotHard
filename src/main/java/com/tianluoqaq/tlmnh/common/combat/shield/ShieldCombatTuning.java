package com.tianluoqaq.tlmnh.common.combat.shield;

/** 副手盾牌格挡冷却与减伤参数（Tuning） */
public final class ShieldCombatTuning {

    public static final int PASSIVE_TICKS = 100;
    public static final int ACTIVE_DURATION = 60;
    public static final int DISABLE_TICKS = 100;
    public static final int SHIELD_CONTROLLER_SNAPSHOT_ZERO_BASE = 0;
    public static final int SHIELD_CONTROLLER_DEPTH = 1;
    public static final int SHIELD_CONTROLLER_DOMAIN_PARAM = 8;
    public static final int SHIELD_CONTROLLER_DOMAIN_PARAM_2 = 64;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int FULL_TURN_DEGREES = 360;
    public static final double MIN_DZ_THRESHOLD = 1.0E-4D;
    public static final double SHIELD_RULES_LAYOUT_DX_OFFSET = .01D;
    public static final double SHIELD_RULES_YAW = 180D;
    public static final int SHIELD_RULES_YAW_2 = 90;
    public static final int MIN_YAW_THRESHOLD = -180;
    public static final int HALF_TURN_DEGREES = 180;

    private ShieldCombatTuning() {}
}
