package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

/** 增强战斗地形采样、空间碰撞与快照参数（Tuning） */
public final class CombatTerrainTuning {

    public static final int TRACE_ENTRY_UNAVAILABLE = -1;

    public static final int UNCLASSIFIED_BLOCK_FLAGS = -1;

    public static final int FOOTPRINT_CORNER_DIRECTION_STRIDE = 2;

    public static final int FOOTPRINT_CORNER_MAX_DIRECTION = 1;

    public static final int FOOTPRINT_CORNER_MIN_DIRECTION = -1;

    public static final int OCCLUSION_ENDPOINT_EXCLUSION_STEPS = 1;

    public static final double NODE_IDENTITY_EPSILON = .0001;
    public static final double STANDING_EPSILON = .001;
    public static final long FEEDBACK_MAX_AGE = 1;
    public static final int GEOMETRY_TTL = 8;
    public static final int HIDDEN_TTL = 100;
    public static final byte SOLID = 1;
    public static final byte WATER = 2;
    public static final byte HAZARD = 4;
    public static final byte UNKNOWN = 8;
    public static final byte OPENABLE = 16;
    public static final byte RAY_BLOCKING = 32;
    public static final byte RAY_UNKNOWN = 64;
    public static final int MAX_BLOCKS = 128;
    public static final int MAX_SHAPES_PER_BLOCK = 16;
    public static final double EPSILON = .0001;
    public static final int RADIUS = 3;
    public static final int MAX_NODES = 49;
    public static final int MAX_WORK = 6000;
    public static final int MAX_RAY_CELLS = 32;
    public static final int CONTINUATION_WORK = 256;
    public static final double STEP_HEIGHT = .5;
    public static final double EPSILON_2 = .001;
    public static final double RETREAT_DISTANCE = 2.5;
    public static final double CONTINUATION_DISTANCE = 2.5;
    public static final double COMBAT_CONDITIONAL_ABS_FIRST_FACTOR = .5;
    public static final double COMBAT_CONDITIONAL_SAME_FACTOR = .0001;
    public static final double COMBAT_CONDITIONAL_FROM_FACTOR = .500001;
    public static final int COMBAT_CONDITIONAL_FROM_PARAM = 2;
    public static final double MAX_DISTANCE_THRESHOLD = 1.5;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int COMBAT_MOVEMENT_DEFAULT_ZERO_BASE = 0;
    public static final int COMBAT_MOVEMENT_DEFAULT_UNIT_STEP = 1;
    public static final int COMBAT_MOVEMENT_DOMAIN_PARAM = 3;
    public static final double MIN_LENGTH_THRESHOLD = 1.0E-6;
    public static final double COMBAT_SNAPSHOT_DOMAIN_FACTOR = .3;
    public static final double COMBAT_SNAPSHOT_DOMAIN_FACTOR_2 = 1.8;
    public static final int COMBAT_SNAPSHOT_DOMAIN_PARAM = 6;
    public static final int MAX_LENGTH_THRESHOLD = 512;
    public static final double COMBAT_SPACE_DOMAIN_FACTOR = .02;
    public static final double COMBAT_SPACE_LAYOUT_PX_OFFSET = .8;
    public static final int COMBAT_SPACE_DOMAIN_PARAM = 5;
    public static final int COMBAT_SPACE_DOMAIN_PARAM_2 = 4;
    public static final double COMBAT_SPACE_DOMAIN_FACTOR_2 = .12;
    public static final double COMBAT_SPACE_DOMAIN_FACTOR_3 = .04;
    public static final int MAX_MIN_X_THRESHOLD = 7;
    public static final int MIN_MIN_X_THRESHOLD = -29999998;
    public static final int MAX_MAX_X_THRESHOLD = 29999997;
    public static final double COMBAT_SPACE_BLOCK_FACTOR = .875;
    public static final double COMBAT_SPACE_EXPAND_FIRST_FACTOR = .25;
    public static final int COMBAT_SPACE_MIN_X = 16;
    public static final int COMBAT_SPACE_QUERY_ALLOWANCE_PARAM = 12;
    public static final double COMBAT_SPACE_GET_BOUNDING_BOX_FIRST_FACTOR = .01;
    public static final double COMBAT_SPACE_GET_BOUNDING_BOX_FOURTH_FACTOR = 1.01;
    public static final double COMBAT_SPACE_GET_BOUNDING_BOX_FIFTH_FACTOR = 2.01;
    public static final double COMBAT_TERRAIN_MAX_SECOND_FACTOR = .65;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR = .10;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_2 = .2;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_3 = .15;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_4 = .025;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_5 = .18;
    public static final int COMBAT_TERRAIN_DOMAIN_PARAM = 20;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_6 = .001;
    public static final double COMBAT_TERRAIN_VEC_SECOND_FACTOR = .85;
    public static final double COMBAT_TERRAIN_HEIGHTS = .55;
    public static final double COMBAT_TERRAIN_PROTECTION_FACTOR = 3.0;
    public static final double COMBAT_TERRAIN_AIM_CLEAR_FACTOR = .05;
    public static final double COMBAT_TERRAIN_RENDER_SCALE_X = 4.0;
    public static final int TARGET_ENTRY_THRESHOLD = -2;
    public static final double COMBAT_TERRAIN_TRACE_ENTRY_FACTOR = 1.0E-10;
    public static final double COMBAT_TERRAIN_DOMAIN_FACTOR_7 = 1.0E-9;
    public static final double THREAT_ESTIMATOR_DOMAIN_FACTOR = 2.7;
    public static final double THREAT_ESTIMATOR_DOMAIN_FACTOR_2 = .1;
    public static final int MIN_DISTANCE_THRESHOLD = 18;
    public static final double MIN_DISTANCE_THRESHOLD_2 = 3.5;
    public static final double THREAT_ESTIMATOR_DOMAIN_FACTOR_3 = .45;
    public static final double THREAT_ESTIMATOR_MIN_FIRST_FACTOR = .28;
    public static final double THREAT_ESTIMATOR_MAX_FIRST_FACTOR = .08;
    public static final double THREAT_ESTIMATOR_DOMAIN_FACTOR_4 = .98;
    public static final double THREAT_ESTIMATOR_VEC_SECOND_FACTOR = 1.25;

    private CombatTerrainTuning() {}
}
