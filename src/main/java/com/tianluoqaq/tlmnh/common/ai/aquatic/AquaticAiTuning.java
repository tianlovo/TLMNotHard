package com.tianluoqaq.tlmnh.common.ai.aquatic;

/** 女仆水下呼吸与水生运动参数（Tuning） */
public final class AquaticAiTuning {

    public static final int START_AIR = 100;
    public static final int RECOVERED_AIR = 290;
    public static final int RANGE = 16;
    public static final int SEARCH_PER_TICK = 128;
    public static final int MAX_SEARCHED = 32768;
    public static final int RETRY_TICKS = 20;
    public static final double SPEED = .5;
    public static final float AQUATIC_CONTROLLER_MAID_SWIM_JUMP_TASK_FIRST_FACTOR = .8F;
    public static final float AQUATIC_CONTROLLER_MOVE_FLYING_THIRD_FACTOR = .01F;
    public static final double AQUATIC_CONTROLLER_TRAVEL_WATER_FACTOR = .9;
    public static final double MIN_GET_EYE_HEIGHT_THRESHOLD = .4;
    public static final int AQUATIC_CONTROLLER_FLUID_JUMP_THRESHOLD_ZERO_BASE = 0;
    public static final double AQUATIC_CONTROLLER_WATER_HEIGHT_X_OFFSET = .001;
    public static final int BREATH_CONTROLLER_DEFAULT_UNIT_STEP = 1;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double BREATH_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR = .5;
    public static final double MIN_DY_THRESHOLD = .09;
    public static final int BREATH_CONTROLLER_TICK_PARAM = 2;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final double MAX_LENGTH_THRESHOLD = 1.0e-5;
    public static final int BREATH_CONTROLLER_YAW = 90;
    public static final int BREATH_CONTROLLER_MAX_FIRST_PARAM = -90;
    public static final double BREATH_CONTROLLER_TRAVEL_WATER_FACTOR = .005;
    public static final double BREATH_CONTROLLER_TRAVEL_WATER_FACTOR_2 = .25;
    public static final int BREATH_CONTROLLER_MOVE_FLYING_SECOND_PARAM = 3;
    public static final int BREATH_CONTROLLER_SET_LOOK_POSITION_FOURTH_PARAM = 30;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final int TRAVERSAL_CONTROLLER_BIT_MASK_8 = 8;
    public static final int TRAVERSAL_CONTROLLER_PLAY_AUX_SFX_AT_ENTITY_SECOND_P = 1003;
    public static final int TRAVERSAL_CONTROLLER_CLOSE_PASSED_DOORS_PARAM = 64;
    public static final double TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FIRST_FACTOR = 0.25;
    public static final double TRAVERSAL_CONTROLLER_GET_BOUNDING_BOX_FOURTH_FACTOR = 1.25;
    public static final double TRAVERSAL_CONTROLLER_CLIMB_FACTOR = 0.8;
    public static final double TRAVERSAL_CONTROLLER_MAX_FIRST_FACTOR = -0.1;
    public static final double TRAVERSAL_CONTROLLER_MIN_FIRST_FACTOR = 0.1;
    public static final double TRAVERSAL_CONTROLLER_CLIMB_FACTOR_2 = -0.25;
    public static final double WATER_PHYSICS_MOTION_Y = .2;
    public static final float WATER_PHYSICS_TRAVEL_FACTOR = .9F;
    public static final double WATER_PHYSICS_FALLING_VELOCITY_SECOND_FACTOR = .08;
    public static final double WATER_PHYSICS_IS_OFFSET_POSITION_IN_LIQUID_SECOND_FA = .6000000238418579;
    public static final double WATER_PHYSICS_MOTION_Y_2 = .30000001192092896;
    public static final double WATER_PHYSICS_ACCELERATION_X_OFFSET = 1.2;
    public static final double WATER_PHYSICS_ACCELERATION_FACTOR = .05;
    public static final float WATER_PHYSICS_ACCELERATION_FACTOR_2 = .02F;
    public static final float HALF_TURN_DEGREES_F = 180F;
    public static final double WATER_PHYSICS_FALLING_VELOCITY_FACTOR = .003;
    public static final int WATER_PHYSICS_ABS_FIRST_PARAM = 16;
    public static final double WATER_PHYSICS_FALLING_VELOCITY_FACTOR_2 = -.003;

    private AquaticAiTuning() {}
}
