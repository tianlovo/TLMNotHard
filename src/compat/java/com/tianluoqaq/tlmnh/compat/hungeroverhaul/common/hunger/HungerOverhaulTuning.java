package com.tianluoqaq.tlmnh.compat.hungeroverhaul.common.hunger;

/** HungerOverhaul 回血速率与饥饿扣血折算常量 */
public final class HungerOverhaulTuning {

    public static final int PEACEFUL_DIFFICULTY = 0;
    public static final int ZERO_INT = 0;
    public static final int EASY_DIFFICULTY = 1;
    public static final int NORMAL_DIFFICULTY = 2;
    public static final int HARD_DIFFICULTY = 3;
    public static final float DIFF_FACTOR_EASY = 0.75F;
    public static final float DIFF_FACTOR_NORMAL = 1.0F;
    public static final float DIFF_FACTOR_HARD = 1.5F;
    public static final float PERCENT_BASE = 100.0F;
    public static final float MIN_RATE_PCT = 1.0F;
    public static final float UNIT_FACTOR = 1.0F;
    public static final float LOW_HEALTH_STEP = 0.01F;
    public static final double LOW_HEALTH_EXPONENT = 1.5D;
    public static final int MIN_REGEN_PERIOD_TICKS = 20;
    public static final int MAX_REGEN_PERIOD_TICKS = 2400;
    public static final float ZERO_DAMAGE = 0.0F;

    private HungerOverhaulTuning() {}
}
