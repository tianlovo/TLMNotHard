package com.tianluoqaq.tlmnh.common.life.hunger.spi;

/** 饥饿策略跨模块窄接口默认数值常量 */
public final class HungerSpiTuning {

    public static final float ZERO_STARVE_DAMAGE = 0.0F;
    public static final float VANILLA_STARVE_DAMAGE = 1.0F;
    public static final int PEACEFUL_DIFFICULTY = 0;
    public static final int EASY_DIFFICULTY = 1;
    public static final int NORMAL_DIFFICULTY = 2;
    public static final float EASY_HEALTH_FLOOR = 10.0F;
    public static final float NORMAL_HEALTH_FLOOR = 1.0F;

    private HungerSpiTuning() {}
}
