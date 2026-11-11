package com.tianluoqaq.tlmnh.common.combat.ballistics;

/** 弹道预测与箭矢拦截求解参数（Tuning） */
public final class BallisticsTuning {

    public static final int MAX_FLIGHT_TICKS = 160;
    public static final double MIN_DISTANCE = 0.25;
    public static final double SPEED_RESIDUAL = 1.0E-9;
    public static final int MAX_CELLS = 4096;
    public static final int CAPACITY = 5;
    public static final int MAX_SPEED_THRESHOLD = 16;
    public static final int MIN_MAX_TICKS_THRESHOLD = 1;
    public static final int REJECTED_INITIAL_VALUE = 0;
    public static final int ARROW_INTERCEPT_LAYOUT_B_OFFSET = 2;
    public static final float ARROW_INTERCEPT_DOMAIN_FACTOR = .8F;
    public static final int MIN_ITERATION_THRESHOLD = 8;
    public static final double MIN_LENGTH_THRESHOLD = 1E-6;
    public static final int ARROW_INTERCEPT_DISCRIMINANT = 4;
    public static final double ARROW_INTERCEPT_ROOTS_Q_OFFSET = -0.5;
    public static final float ARROW_PHYSICS_ARROW_PHYSICS_FIRST_FACTOR = 0.99F;
    public static final float ARROW_PHYSICS_ARROW_PHYSICS_SECOND_FACTOR = 0.05F;
    public static final float ARROW_PHYSICS_ARROW_PHYSICS_FIRST_FACTOR_2 = 0.8F;
    public static final float GRAVITY_FREE_ON_UPDATE_FACTOR = .6F;
    public static final int HALF_TURN_DEGREES = 180;
    public static final double ARROW_MEDIUM_AT_X0_OFFSET = .001;
    public static final float ARROW_MEDIUM_FLOOR_DOUBLE_FIRST_FACTOR = .4F;
    public static final double ARROW_MEDIUM_PUSH = .014;
    public static final float ARROW_MEDIUM_MEDIUM_FIRST_FACTOR = .99F;
    public static final double OBSERVED_TARGET_OBSERVE_FACTOR = 1.5;
    public static final double UNIT_AXIS_SCALE_D = 1.0;
    public static final float SOURCE_BOW_MAX_FIRST_FACTOR = 1.6F;
    public static final float SOURCE_BOW_MIN_FIRST_FACTOR = 3.2F;
    public static final float SOURCE_BOW_MIN_SECOND_FACTOR = 10F;
    public static final float SOURCE_BOW_INACCURACY_FACTOR = 1F;
    public static final float SOURCE_BOW_MAX_FIRST_FACTOR_2 = 0F;
    public static final float SOURCE_BOW_MIN_FIRST_FACTOR_2 = .9F;
    public static final float SOURCE_BOW_MIN_SECOND_FACTOR_2 = 100F;
    public static final float SOURCE_BOW_BASE_DAMAGE_FACTOR = 2F;
    public static final double SOURCE_BOW_TRIANGLE_FIRST_FACTOR = .11;
    public static final double SOURCE_BOW_TRIANGLE_SECOND_FACTOR = .57425;
    public static final double MIN_LENGTH_THRESHOLD_2 = 1.0E-4;
    public static final double SOURCE_BOW_SPREAD = .0172275;

    private BallisticsTuning() {}
}
