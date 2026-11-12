package com.tianluoqaq.tlmnh.common.life.hazard;

/** 热物品扫描周期、丢弃阈值、掉落延迟与防误捡冷却常量 */
public final class HotItemHazardTuning {

    public static final int ZERO_COUNT = 0;
    public static final int SCAN_PERIOD_TICKS = 120;
    public static final int BURN_COUNT_BEFORE_DROP = 3;
    public static final int PICKUP_COOLDOWN_TICKS = 200;
    public static final int DROP_PICKUP_DELAY_TICKS = 40;
    public static final float DROP_HEIGHT_OFFSET = 0.25F;
    public static final int BUBBLE_EXIST_TICKS = 120;
    public static final int BUBBLE_PRIORITY = 10;
    public static final int NBT_TAG_COMPOUND = 10;

    private HotItemHazardTuning() {}
}
