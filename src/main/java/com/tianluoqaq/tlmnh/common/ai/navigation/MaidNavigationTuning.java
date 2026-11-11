package com.tianluoqaq.tlmnh.common.ai.navigation;

/** 女仆寻路导航器核心参数（Tuning） */
public final class MaidNavigationTuning {

    public static final int MIN_COUNT_THRESHOLD = 2;
    public static final int PATH_NAVIGATOR_FLOOR_FIRST_PARAM = 16;
    public static final int QUERY_SINGLE_UNIT = 1;
    public static final int CREATE_VECTOR_HELPER_INITIAL_VALUE = 0;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double PATH_NAVIGATOR_FLOOR_FIRST_FACTOR = .5;
    public static final float MAX_WIDTH_THRESHOLD = .75F;
    public static final float MIDPOINT_DIVISOR_F = 2F;
    public static final float PATH_NAVIGATOR_OFFSET = 1F;
    public static final int PATH_NAVIGATOR_NEXT_IN_DIRECTION_PARAM = 4;
    public static final int MAX_LAST_STUCK_CHECK_THRESHOLD = 100;
    public static final float PATH_NAVIGATOR_DETECT_STUCK_FACTOR = 100F;
    public static final float PATH_NAVIGATOR_DETECT_STUCK_FACTOR_2 = .25F;
    public static final int PATH_NAVIGATOR_TIMEOUT_LIMIT = 20;
    public static final int PATH_NAVIGATOR_DETECT_STUCK_PARAM = 3;

    private MaidNavigationTuning() {}
}
