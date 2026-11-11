package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原版对齐模式核心跟随与行为参数（Tuning） */
public final class FaithfulAiTuning {

    public static final int ACTIVITY_STATE_LAST_SCHEDULE_UPDATE = -9999;
    public static final int MAX_LAST_SCHEDULE_UPDATE_THRESHOLD = 20;
    public static final float ACTIVITY_STATE_SET_POSITION_WALK_AND_LOOK_SECOND_FAC = .7F;
    public static final int ACTIVITY_STATE_SET_POSITION_WALK_AND_LOOK_THIRD_PARA = 3;
    public static final float FAITHFUL_BRAIN_MAID_FOLLOW_OWNER_TASK_FIRST_FACTOR = .5F;
    public static final int FAITHFUL_BRAIN_MAID_FOLLOW_OWNER_TASK_SECOND_PARAM = 2;
    public static final float FAITHFUL_BRAIN_MAID_SWIM_JUMP_TASK_FIRST_FACTOR = .8F;
    public static final float FAITHFUL_BRAIN_MAID_PICKUP_ENTITIES_TASK_FIRST_FACTO = .6F;
    public static final int FAITHFUL_BRAIN_MAID_SNOWBALL_TARGET_TASK_FIRST_PARAM = 50;
    public static final int FAITHFUL_BRAIN_CLIMB_DEADLINE = 60;
    public static final int NEXT_WORLD_INT_SINGLE_UNIT = 1;
    public static final int FAITHFUL_BRAIN_SNOW_DEADLINE = 1200;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int COMBAT_NAVIGATION_OUTCOME_INITIAL_VALUE = 0;
    public static final double MODERN_FOLLOW_GET_DISTANCE_SQ_FIRST_FACTOR = .5;
    public static final int MODERN_FOLLOW_TEMPTING_VISIBLE_OWNER_PARAM = 36;
    public static final int MODERN_FOLLOW_WORK_POINT_NEAR_OWNER_PARAM = 64;
    public static final int MIN_I_THRESHOLD = 10;
    public static final int MODERN_FOLLOW_NEXT_INT_FIRST_PARAM = 7;
    public static final int MODERN_FOLLOW_NEXT_INT_FIRST_PARAM_2 = 4;
    public static final int MODERN_FOLLOW_SET_ENTITY_STATE_SECOND_PARAM = 46;
    public static final double MODERN_FOLLOW_COLLIDES_SECOND_FACTOR = .001;
    public static final double MODERN_FOLLOW_COLLIDES_SECOND_FACTOR_2 = .0625;
    public static final int MODERN_FOLLOW_COMBAT_NODE_ARRIVED_PARAM = 256;
    public static final double MODERN_FOLLOW_NATIVE_MOVE_DISPATCH_FACTOR = .0001;
    public static final int MODERN_FOLLOW_LOG_SECOND_PARAM = 12;
    public static final int HALF_TURN_DEGREES = 180;
    public static final int MODERN_FOLLOW_YAW = 90;
    public static final int MODERN_FOLLOW_CLAMP_FLOAT_SECOND_PARAM = -10;
    public static final int MODERN_FOLLOW_BIT_MASK_8 = 8;
    public static final int MODERN_FOLLOW_SET_LOOK_POSITION_FOURTH_PARAM = 30;
    public static final int MODERN_FOLLOW_DEFAULT_PROJECTILE_RANGE_PARAM = 15;
    public static final float MODERN_FOLLOW_CAN_SNOWBALL_FIGHT_FACTOR = .15F;
    public static final float MODERN_FOLLOW_THROW_SNOWBALL_AT_Y_OFFSET = 3.0F;
    public static final double MODERN_FOLLOW_SET_THROWABLE_HEADING_SECOND_FACTOR = .15D;
    public static final float MODERN_FOLLOW_SET_THROWABLE_HEADING_FOURTH_FACTOR = 1.6F;
    public static final float MODERN_FOLLOW_THROW_SNOWBALL_AT_FACTOR = .4F;

    private FaithfulAiTuning() {}
}
