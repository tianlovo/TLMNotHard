package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

/** 增强战斗动作执行与跳劈参数（Tuning） */
public final class EnhancedCombatTuning {

    public static final int TARGETS = 8;
    public static final int PROJECTILES = 12;
    public static final int FRIENDS = 12;
    public static final int CHUNK_COUNT = 25;
    public static final int SCAN_CHUNKS_PER_TICK = 4;
    public static final int PREPARATION_CHUNKS_PER_TICK = 8;
    public static final int MAX_DEFERRED = 32;
    public static final int MAX_SAMPLED = 128;
    public static final int FRIENDS_INCOMPLETE = 1024;
    public static final int TARGET_INCOMPLETE = 2048;
    public static final int GEOMETRY_MISSING = 1;
    public static final int GEOMETRY_UNKNOWN = 2;
    public static final int GEOMETRY_STALE = 4;
    public static final int WEAPONS_PARTIAL = 8;
    public static final int SCAN_INCOMPLETE = 16;
    public static final int SCAN_STALE = 32;
    public static final int PROJECTILE_INCOMPLETE = 64;
    public static final int OBSERVATION_INCOMPLETE = 128;
    public static final int NO_CAPTURE = 256;
    public static final int OBSERVATION_DENIED = 512;
    public static final int CAPACITY = 128;
    public static final float APPROACH_SPEED = .6F;
    public static final double HEAD_SWEEP = 1.3;
    public static final int JUMP_DRIFT_TICKS = 16;
    public static final double JUMP_SWEEP_EPSILON = .0001;
    public static final int COMBAT_OBSERVATION_OBSERVE_PARAM = 2;
    public static final int COMBAT_OBSERVATION_OBSERVE_UNIT_STEP = 1;
    public static final int COMBAT_OBSERVATION_OBSERVE_ZERO_BASE = 0;
    public static final int MIN_SIZE_THRESHOLD = 7;
    public static final int MAX_GET_VALUE_THRESHOLD = 100;
    public static final int COMBAT_OBSERVATION_COLLECT_URGENT_PARAM = 8;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final int COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM = 12;
    public static final int COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM_2 = 5;
    public static final int COMBAT_OBSERVATION_SECTION = 16;
    public static final double COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR = .8;
    public static final double COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR_2 = .7;
    public static final float COMBAT_OBSERVATION_MAX_FIRST_FACTOR = .1F;
    public static final float COMBAT_OBSERVATION_MAX_SECOND_FACTOR = 4F;
    public static final int MAX_SIZE_THRESHOLD = 32;
    public static final double UNIT_AXIS_SCALE_D = 1.0;
    public static final int COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM = 20;
    public static final int COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2 = 30;
    public static final double COMBAT_OBSERVATION_WIDTH = .5;
    public static final int COMBAT_OBSERVATION_SAMPLE_ARROW_NOW_PARAM = 24;
    public static final double MIN_MOTION_Z_THRESHOLD = .001;
    public static final double COMBAT_OBSERVATION_DOMAIN_FACTOR = .20;
    public static final double COMBAT_OBSERVATION_DOMAIN_FACTOR_2 = .3;
    public static final double COMBAT_OBSERVATION_DOMAIN_FACTOR_3 = 1.8;
    public static final float COMBAT_OBSERVATION_DOMAIN_FACTOR_4 = .99F;
    public static final int COMBAT_OBSERVATION_DOMAIN_PARAM = 6;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double COMBAT_OBSERVATION_MASK = .01;
    public static final double COMBAT_OBSERVATION_MASK_2 = 1.01;
    public static final double COMBAT_OBSERVATION_MASK_3 = 2.01;
    public static final int COMBAT_OBSERVATION_COLLECT_GEOMETRY_PARAM = 3;
    public static final float COMBAT_OBSERVATION_DOMAIN_FACTOR_5 = .05F;
    public static final double MAX_MOTION_Z_THRESHOLD = .0001;
    public static final double UNIFIED_COMBAT_ROUTE_DESTINATION_FACTOR = .35;

    private EnhancedCombatTuning() {}
}
