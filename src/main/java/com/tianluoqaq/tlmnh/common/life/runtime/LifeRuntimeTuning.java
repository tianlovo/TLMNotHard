package com.tianluoqaq.tlmnh.common.life.runtime;

/** 女仆日常作息运行期参数（Tuning） */
public final class LifeRuntimeTuning {

    public static final int MISSING_CAKE_SLOT = -1;

    public static final int SCAN_INTERVAL = 20;
    public static final int MAX_ACTION_TICKS = 200;
    public static final double LIFE_CONTROLLER_GET_DISTANCE_SQ_FIRST_FACTOR = .5;
    public static final int LOW_TWO_BITS_MASK = 3;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final double LIFE_CONTROLLER_SET_POSITION_SECOND_FACTOR = 0.5625;
    public static final int LIFE_CONTROLLER_MOTION_Z = 0;
    public static final double LIFE_CONTROLLER_TICK_ACTIVE_FACTOR = 1.5;
    public static final int MIN_DISTANCE_THRESHOLD = 4;
    public static final int LIFE_CONTROLLER_NEXT_PATH = 20;
    public static final double LIFE_CONTROLLER_REQUEST_FIFTH_FACTOR = 0.6D;
    public static final int LIFE_CONTROLLER_SCOPE_ALLOWED_PARAM = 144;
    public static final int CONTAINS_SINGLE_UNIT = 1;
    public static final int LIFE_CONTROLLER_DOMAIN_PARAM = 5;
    public static final int MIN_PART_THRESHOLD = 2;
    public static final float LIFE_CONTROLLER_PLAY_SOUND_SECOND_FACTOR = 1.0F;
    public static final float HALF_FACTOR_F = 0.5F;
    public static final int LIFE_CONTROLLER_STOP_PARAM = 16;

    private LifeRuntimeTuning() {}
}
