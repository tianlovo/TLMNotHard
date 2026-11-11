package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

/** 水陆两栖与陆行节点评估器参数（Tuning） */
public final class NavigationEvalTuning {

    public static final int OFFSET_Z_INDEX = 2;

    public static final int OFFSET_Y_INDEX = 1;

    public static final int OFFSET_X_INDEX = 0;

    public static final int MAX_COLLISION_CELLS = 32;
    public static final int COST_INITIAL_VALUE = 0;
    public static final int AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM = 6;
    public static final int AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2 = 4;
    public static final int AMPHIBIOUS_EVALUATOR_NEIGHBORS_PARAM = 10;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP = 1;
    public static final int AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM = 2;
    public static final double MAX_Y_THRESHOLD = .5;
    public static final int NATIVE_PATH_SEA_LEVEL_PARAM = 63;
    public static final int PATH_HEAP_DOMAIN_PARAM = 128;
    public static final int PATH_SEARCH_DOMAIN_PARAM = 32;
    public static final float PATH_SEARCH_LAYOUT_H_OFFSET = 1.5F;
    public static final int PATH_SEARCH_UPDATE_PARAM = 100;
    public static final float WALK_EVALUATOR_WIDTH = 1F;
    public static final double WALK_EVALUATOR_MAX_FIRST_FACTOR = 1.125;
    public static final double MIDPOINT_DIVISOR_D = 2.0;
    public static final double WALK_EVALUATOR_DOMAIN_FACTOR = .001;
    public static final double WALK_EVALUATOR_DOMAIN_FACTOR_2 = .002;
    public static final int WALK_EVALUATOR_STEPS = 3;
    public static final int WALK_EVALUATOR_BIT_MASK_67108863 = 67108863;
    public static final int WALK_EVALUATOR_BIT_SHIFT_38 = 38;
    public static final int WALK_EVALUATOR_BIT_SHIFT_12 = 12;
    public static final int WALK_EVALUATOR_BIT_MASK_4095 = 4095;

    private NavigationEvalTuning() {}
}
