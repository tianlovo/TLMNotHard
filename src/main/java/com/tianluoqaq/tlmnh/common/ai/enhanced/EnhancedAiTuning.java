package com.tianluoqaq.tlmnh.common.ai.enhanced;

/** 增强模式 AI 核心装配参数（Tuning） */
public final class EnhancedAiTuning {

    public static final int CHOICE_INTERVAL = 20;
    public static final int LOOK_TICKS = 40;
    public static final int WALK_TICKS = 100;
    public static final int REPLAN_TICKS = 4;
    public static final int FAILED_APPROACH_TICKS = 200;
    public static final int RETRY_TICKS = 100;
    public static final int MAX_FAILED_TARGETS = 32;
    public static final int RECOVERY_RETRY_TICKS = 40;
    public static final int SCAN_INTERVAL = 20;
    public static final int MAX_BED_APPROACH_TICKS = 200;
    public static final int MAX_SNACK_APPROACH_TICKS = 160;
    public static final int CANDIDATE_RETRY_TICKS = 100;
    public static final int MAX_FAILED_CANDIDATES = 32;
    public static final int MAX_CANDIDATES = 64;
    public static final int RETRY_TICKS_2 = 20;
    public static final double DANGER_DISTANCE_SQUARED = 64;
    public static final int FAILED_RETRY_TICKS = 100;
    public static final double RECONSIDER_DISTANCE_SQUARED = 1;
    public static final int PROBE_TICKS = 10;
    public static final int POINT_TICKS = 20;
    public static final int ROUTE_TICKS = 40;
    public static final int MAX_PLAY_TICKS = 1200;
    public static final int RETRY_TICKS_3 = 40;
    public static final int PICKUP_DELAY = 25;
    public static final int ENHANCED_AMBIENT_NEXT_CHOICE = 0;
    public static final int ENHANCED_AMBIENT_OFFER_UNIT_STEP = 1;
    public static final int ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_FIRST_PARAM = 5;
    public static final int ENHANCED_AMBIENT_SAMPLE_ENHANCED_LAND_SECOND_PARAM = 3;
    public static final double ENHANCED_AMBIENT_OFFER_OBSERVE_FACTOR = .3;
    public static final double ENHANCED_AMBIENT_OFFER_WALK_FACTOR = .1;
    public static final int ENHANCED_AMBIENT_VISIBLE_OWNER_NOW_FIRST_PARAM = 6;
    public static final int ENHANCED_AMBIENT_LEGAL_LOOK_PARAM = 25;
    public static final int ENHANCED_AMBIENT_CLOSER_THAN_SECOND_PARAM = 2;
    public static final float ENHANCED_AMBIENT_SET_WALK_AND_LOOK_TARGET_MEMORIES_S = .6F;
    public static final float ENHANCED_AMBIENT_SET_POSITION_WALK_AND_LOOK_SECOND_F = .3F;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int ENHANCED_HOME_RECOVERY_DISTANCE = 4;
    public static final float ENHANCED_HOME_SET_POSITION_WALK_AND_LOOK_SECOND_FACT = .7F;
    public static final double ENHANCED_LIFE_DISTANCE = .5;
    public static final int ENHANCED_PANIC_FIND_ENHANCED_LAND_AWAY_FIRST_PARAM = 10;
    public static final int ENHANCED_RANGED_DOMAIN_PARAM = -2;
    public static final int MAX_SIZE_THRESHOLD = 32;
    public static final double ENHANCED_RANGED_EXECUTE_FACTOR = .75;
    public static final float ENHANCED_SNOWBALL_OFFER_FACTOR = 32F;
    public static final int ENHANCED_SNOWBALL_LEGAL_TARGET_PARAM = 256;
    public static final int ENHANCED_SNOWBALL_NEXT_THROW = 50;
    public static final int MAX_SNOWBALL_ANIMATION_STARTED_AT_THRESHOLD = 1750;

    private EnhancedAiTuning() {}
}
