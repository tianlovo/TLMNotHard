package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 增强战斗几何窗口、动态范围与推演预算参数（Tuning） */
public final class CombatBudgetTuning {

    public static final int OBSERVATION_TTL = 8;

    public static final int MAX_OWNERS = 256;
    public static final int WIDTH = 9;
    public static final int HEIGHT = 6;
    public static final int FLOOR_DEPTH = 2;
    public static final int RADIUS = WIDTH / 2;
    public static final int MIN_UNPRODUCTIVE_ATTEMPTS = 3;
    public static final int MIN_UNPRODUCTIVE_SPAN_TICKS = 20;
    public static final long MIN_STALE_REJECTION_GAP_TICKS = 40;
    public static final int UNPRODUCTIVE_COOLDOWN_TICKS = 100;
    public static final double FRIEND_MELEE_RADIUS_SQ = 6.25;
    public static final int PERIOD = 40;
    public static final int SCOUT_TICKS = 8;
    public static final int EVENT_TICKS = 20;
    public static final int ALERT_TICKS = 60;
    public static final int MAX_TRACKED_REJECTIONS = 32;
    public static final double MAID_TARGET_LIMIT = 24;
    public static final double OWNER_HURT_BONUS = .20;
    public static final double MAID_HURT_BONUS = .10;
    public static final double CURRENT_BONUS = .015;
    public static final double FRIEND_ENGAGED_PENALTY = .08;
    public static final int MAX_CANDIDATES = 12;
    public static final int HYPOTHESES = 3;
    public static final int HORIZON = 40;
    public static final int STEP = 2;
    public static final int MAX_ADVANCES = MAX_CANDIDATES * HYPOTHESES * HORIZON / STEP;
    public static final int PREPARATIONS_PER_TICK = 8;
    public static final int PREPARATION_TICKS = 6;
    public static final int REQUEST_TTL = 2;
    public static final int FULL_PER_TICK = 2;
    public static final int COMBAT_DIAGNOSTIC_DROPPED = 0;
    public static final double MAX_HALF_WIDTH_THRESHOLD = .5;
    public static final int COMBAT_RANGE_URGENCY_PARAM = 2;
    public static final int COMBAT_RANGE_URGENCY_UNIT_STEP = 1;
    public static final long COMBAT_RANGE_MAX_FIRST_LIMIT = 1L;
    public static final int COMBAT_WORK_DOMAIN_PARAM = 32;
    public static final int COMBAT_WORK_DOMAIN_PARAM_2 = 128;
    public static final int COMBAT_WORK_DOMAIN_PARAM_3 = 640;
    public static final int COMBAT_WORK_DOMAIN_PARAM_4 = 512;
    public static final int COMBAT_WORK_DOMAIN_PARAM_5 = 24;
    public static final int COMBAT_WORK_DOMAIN_PARAM_6 = 16;
    public static final int COMBAT_WORK_DOMAIN_PARAM_7 = 12;
    public static final int COMBAT_WORK_DOMAIN_PARAM_8 = 40000;
    public static final int COMBAT_WORK_DOMAIN_PARAM_9 = 96;
    public static final int COMBAT_WORK_DOMAIN_PARAM_10 = 48;
    public static final int COMBAT_WORK_DOMAIN_PARAM_11 = 64;
    public static final int COMBAT_WORK_DOMAIN_PARAM_12 = 2400;
    public static final int COMBAT_WORK_DOMAIN_PARAM_13 = 20;
    public static final int COMBAT_WORK_DOMAIN_PARAM_14 = 576;
    public static final int COMBAT_WORK_DOMAIN_PARAM_15 = 4096;
    public static final int COMBAT_WORK_DOMAIN_PARAM_16 = 3072;
    public static final int COMBAT_WORK_DOMAIN_PARAM_17 = 2048;
    public static final int COMBAT_WORK_DOMAIN_PARAM_18 = 256;
    public static final int COMBAT_WORK_DOMAIN_PARAM_19 = 1440;
    public static final int COMBAT_WORK_DOMAIN_PARAM_20 = 90000;
    public static final int COMBAT_WORK_OBSERVATION_RESERVE_PARAM = 7;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int COMBAT_WORLD_DOMAIN_PARAM = 31;
    public static final int COMBAT_WORLD_LAYOUT_V_OFFSET = 3;

    private CombatBudgetTuning() {}
}
