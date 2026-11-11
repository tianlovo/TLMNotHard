package com.tianluoqaq.tlmnh.common.ai.combat;

/** 女仆基础战斗目标扫描与索敌参数（Tuning） */
public final class AiCombatTuning {

    public static final int SCAN_INTERVAL = 10;
    public static final int PATH_INTERVAL = 10;
    public static final int PATH_RETRY_INTERVAL = 40;
    public static final int ATTACK_INTERVAL = 20;
    public static final int HIDDEN_TIMEOUT = 40;
    public static final int PURSUIT_TIMEOUT = 200;
    public static final int STUCK_INTERVAL = 40;
    public static final int MAX_PATH_FAILURES = 3;
    public static final double LAST_SEEN_REACH_SQ = 2.25D;
    public static final double OWNER_RADIUS_SQ = 256;
    public static final int RECENT_ATTACK_TICKS = 100;
    public static final float PROJECTILE_RANGE = 15;
    public static final int COMBAT_CONTROLLER_CONSECUTIVE_PATH_FAILURES = 0;
    public static final double MIN_MOVEMENT_THRESHOLD = 0.0625;
    public static final int TARGET_GET_ID_THRESHOLD = 3;
    public static final double COMBAT_TARGETING_WIDTH = 2.0D;
    public static final int ADD_TASK_SINGLE_UNIT = 1;
    public static final double CREEPER_AVOIDANCE_EXPAND_FIRST_FACTOR = 6.0D;
    public static final double CREEPER_AVOIDANCE_EXPAND_SECOND_FACTOR = 3.0D;
    public static final int CREEPER_AVOIDANCE_SHOULD_EXECUTE_PARAM = 16;
    public static final int CREEPER_AVOIDANCE_SHOULD_EXECUTE_PARAM_2 = 7;
    public static final double CREEPER_AVOIDANCE_ELIGIBLE_FACTOR = 0.8D;
    public static final double CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_2 = 1.0D;
    public static final int MIN_SLOT_THRESHOLD = 4;
    public static final double CREEPER_AVOIDANCE_ELIGIBLE_FACTOR_3 = 0.7D;
    public static final float CREEPER_AVOIDANCE_MAX_FIRST_FACTOR = 4.0F;
    public static final float CREEPER_AVOIDANCE_MAX_SECOND_FACTOR = 0.1F;
    public static final double HALF_FACTOR_D = 0.5D;
    public static final double CREEPER_AVOIDANCE_SET_SPEED_FIRST_FACTOR = 49.0D;
    public static final double CREEPER_AVOIDANCE_SET_SPEED_FIRST_FACTOR_2 = 1.2D;
    public static final int MELEE_ATTACK_SPEND_SECOND_PARAM = 6;
    public static final int MELEE_ATTACK_MAX_SECOND_PARAM = 20;
    public static final float HALF_TURN_DEGREES_F = 180.0F;
    public static final double MELEE_ATTACK_HURT_FOR_UNIFIED_FACTOR = 0.1D;
    public static final double MELEE_ATTACK_HURT_FOR_UNIFIED_FACTOR_2 = 0.6D;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double MIN_GET_AS_DOUBLE_THRESHOLD = .3;
    public static final double STRAFING_STATE_TICK_FACTOR = .5;
    public static final double STRAFING_STATE_TICK_FACTOR_2 = .2;
    public static final float STRAFING_STATE_TICK_FACTOR_3 = -.5F;
    public static final float STRAFING_STATE_TICK_FACTOR_4 = .5F;

    private AiCombatTuning() {}
}
