package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

/** 原版对齐模式跟随传送与采样参数（Tuning） */
public final class FaithfulFollowTuning {

    public static final double FALLBACK_ANGLE = 1.5707963705062866;
    public static final int ATTEMPTS = 10;
    public static final double HALF_PI_FLOAT = 1.5707963705062866D;
    public static final int MIN_RADIUS_THRESHOLD = 3;
    public static final int MAX_RADIUS_THRESHOLD = 32;
    public static final int DRY_PLATFORM_BEGIN_FRAME_ZERO_BASE = 0;
    public static final float TARGET_WIDTH_THRESHOLD = 0.6F;
    public static final float TARGET_HEIGHT_THRESHOLD = 1.5F;
    public static final int TARGET_HORIZONTAL_RANGE_THRESHOLD = 10;
    public static final int TARGET_VERTICAL_RANGE_THRESHOLD = 7;
    public static final double TARGET_ANGLE_THRESHOLD = 1.5707963705062866D;
    public static final double MIN_X_THRESHOLD = -30000000D;
    public static final double MAX_X_THRESHOLD = 30000000D;
    public static final double CENTER_OFFSET_D = 0.5D;
    public static final int TARGET_GET_HEIGHT_THRESHOLD = 256;
    public static final long DRY_PLATFORM_FALLBACK_CANDIDATE_GUARD_LIMIT = 10L;
    public static final long DRY_PLATFORM_FALLBACK_CANDIDATE_GUARD_LIMIT_2 = 7L;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final float DRY_PLATFORM_PATHFINDING_MALUS_FACTOR = 0.0F;
    public static final int TARGET_RAW_THRESHOLD = 15;
    public static final float DRY_PLATFORM_LIGHT_PATHFINDING_COST_SECOND_FACTOR = 1.0F;
    public static final float DRY_PLATFORM_SET_LOOK_POSITION_FOURTH_FACTOR = 10.0F;
    public static final float HALF_FACTOR_F = 0.5F;
    public static final int FOLLOW_CORE_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM = 2;
    public static final int FOLLOW_CORE_FOLLOW_END_TIMESTAMP = 60;
    public static final int NEXT_WORLD_INT_SINGLE_UNIT = 1;
    public static final int FOLLOW_CORE_FOLLOW_CORE_LOOK_DRIVER_PARAM = 45;
    public static final int FOLLOW_CORE_FOLLOW_CORE_LOOK_DRIVER_PARAM_2 = 90;
    public static final int FOLLOW_CORE_FOLLOW_CORE_MOVE_DRIVER_PARAM = 150;
    public static final int FOLLOW_CORE_FOLLOW_CORE_MOVE_DRIVER_PARAM_2 = 250;
    public static final int FOLLOW_CORE_WORLD_INT_SECOND_PARAM = 40;
    public static final int FOLLOW_CORE_TICK_PARAM = 4;
    public static final double CENTER_OFFSET_D_2 = 0.5;
    public static final int FOLLOW_FALLBACK_DOMAIN_PARAM = 257;
    public static final double FOLLOW_FALLBACK_ANGLE = 256.0D;
    public static final double FOLLOW_FALLBACK_DISTANCE = 1.0D;
    public static final float FOLLOW_FALLBACK_DIRECTION_FACTOR = 2.0F;
    public static final float FOLLOW_FALLBACK_LEVEL = 15.0F;
    public static final float FOLLOW_FALLBACK_LIGHT_PATHFINDING_COST_FACTOR = 4.0F;
    public static final float FOLLOW_FALLBACK_BRIGHTNESS = 3.0F;
    public static final double FOLLOW_FALLBACK_MAPPED_ATAN2_FACTOR = 6.0D;
    public static final double FOLLOW_FALLBACK_CORRECTION = 0.16666666666666666D;
    public static final double FOLLOW_FALLBACK_RESULT = 1.5707963267948966D;
    public static final double FOLLOW_FALLBACK_RESULT_2 = 3.141592653589793D;
    public static final long FOLLOW_FALLBACK_BITS = 6910469410427058090L;
    public static final double FOLLOW_FALLBACK_FAST_INV_SQRT_FACTOR = 1.5D;
    public static final double FOLLOW_MOVE_VEC3_FIRST_FACTOR = .5;

    private FaithfulFollowTuning() {}
}
