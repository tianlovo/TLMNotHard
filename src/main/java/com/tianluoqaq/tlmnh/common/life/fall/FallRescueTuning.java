package com.tianluoqaq.tlmnh.common.life.fall;

/** 高坠预警距离、水源放置回收与贴地放船回收常量 */
public final class FallRescueTuning {

    public static final int NBT_TAG_COMPOUND = 10;
    public static final int BOOTS_EQUIPMENT_SLOT = 1;
    public static final int ZERO_META = 0;
    public static final int MIN_WORLD_Y = 0;
    public static final int NOT_FOUND_SLOT = -1;
    public static final int SINGLE_STACK = 1;
    public static final float ZERO_FALL_DISTANCE = 0.0F;

    public static final float MIN_TRIGGER_FALL_DISTANCE = 5.0F;
    public static final double TRIGGER_MAX_VY = -0.35D;
    public static final int MAX_SCAN_BLOCKS_DOWN = 12;
    public static final double MIN_WATER_WINDOW_BLOCKS = 2.6D;
    public static final double VY_WINDOW_MULTIPLIER = 2.5D;
    public static final double VY_WINDOW_BASE = 1.2D;
    public static final double MAX_BOAT_WINDOW_BLOCKS = 2.6D;
    public static final double BOAT_VY_PADDING = 1.0D;

    public static final int BLOCK_UPDATE_FLAGS = 3;
    public static final int WATER_PICKUP_LEASE_TICKS = 30;
    public static final int WATER_MIN_SETTLE_TICKS = 2;

    public static final double BOAT_SPAWN_ABOVE_TOP_OFFSET = 0.05D;
    public static final double SURFACE_EPSILON = 0.01D;
    public static final int BOAT_DISMOUNT_SETTLE_TICKS = 5;
    public static final float DROP_HEIGHT_OFFSET = 0.2F;

    public static final int NBT_TAG_LONG = 4;
    public static final int PLACE_SIDE_UP = 1;

    private FallRescueTuning() {}
}
