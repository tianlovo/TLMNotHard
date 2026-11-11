package com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion;

/** 增强模式移动与骑乘控制参数（Tuning） */
public final class EnhancedLocomotionTuning {

    public static final int NEARBY_RETRY_TICKS = 10;
    public static final int EXHAUSTED_RETRY_TICKS = 20;
    public static final int RECOVERY_RETRY_TICKS = 20;
    public static final int FAILURE_RETRY_TICKS = 20;
    public static final int NO_PROGRESS_TICKS = 40;
    public static final double REPLAN_DISTANCE_SQUARED = 4;
    public static final int MIN_AIR_SUPPLY_THRESHOLD = 100;
    public static final int OFFER_TYPED_SINGLE_UNIT = 1;
    public static final int ENHANCED_BREATHING_NEXT_RELEASE = 20;
    public static final int ENHANCED_BREATHING_CANDIDATE_INDEX = 0;
    public static final int ENHANCED_BREATHING_FIND_AIR_WITH_BFS_FIRST_PARAM = 16;
    public static final int ENHANCED_BREATHING_EXECUTE_Z_OFFSET = 2;
    public static final float ENHANCED_BREATHING_SET_AIR_WALK_AND_LOOK_SECOND_FACT = .5F;
    public static final float MIN_NEXT_FLOAT_THRESHOLD = .8F;
    public static final int ENHANCED_FOLLOW_OFFER_PARAM = 6;
    public static final int ENHANCED_RIDE_MAID_WITHIN_OWNER_RANGE_FIRST_PARAM = 3;

    private EnhancedLocomotionTuning() {}
}
