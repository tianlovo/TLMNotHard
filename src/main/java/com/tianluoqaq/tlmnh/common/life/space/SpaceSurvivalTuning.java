package com.tianluoqaq.tlmnh.common.life.space;

/** 星系缺氧伤害递增与外星热力 ±22 阶梯常量 */
public final class SpaceSurvivalTuning {

    public static final float UNIT_FACTOR = 1.0F;
    public static final int NBT_TAG_COMPOUND = 10;
    public static final int ZERO_INT = 0;
    public static final int MIN_POSITIVE_ONE = 1;
    public static final int STEP_MINUS_ONE = -1;
    public static final float MIN_THERMAL_MODIFIER_ABS = 0.001F;

    public static final int SUFFOCATION_BASE_FACTOR = 2;
    public static final int SUFFOCATION_DIVISOR = 2;

    public static final int MAX_THERMAL_LEVEL = 22;
    public static final int SAFE_THERMAL_BAND = 14;
    public static final int WARN_THERMAL_THRESHOLD = 15;
    public static final int BASE_THERMAL_INTERVAL = 20;
    public static final float THERMAL_DAMAGE_AMOUNT = 1.5F;
    public static final int SLOWNESS_DURATION_TICKS = 40;

    public static final float THERMAL_INTERVAL_NUMERATOR = 200.0F;
    public static final float THERMAL_NORMALISE_NUMERATOR = 150.0F;
    public static final float THERMAL_STRENGTH_DIVISOR = 2.0F;
    public static final int THERMAL_PIECE_COUNT = 4;
    public static final int ZERO_MOD_NORMALISE = 2;
    public static final int SAFE_DIM_NORMALISE = 3;
    public static final int THERMAL_EFFECT_DURATION = 5;
    public static final int THERMAL_EFFECT_AMPLIFIER = 2;

    private SpaceSurvivalTuning() {}
}
