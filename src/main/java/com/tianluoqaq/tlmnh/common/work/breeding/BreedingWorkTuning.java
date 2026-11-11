package com.tianluoqaq.tlmnh.common.work.breeding;

/** 育种田块 3x3 网格、巡查节拍、寻路距离与工具挖掘参数 */
public final class BreedingWorkTuning {

    public static final int ZERO = 0;
    public static final int ONE = 1;
    public static final int TWO = 2;
    public static final int THREE = 3;
    public static final int FOUR = 4;
    public static final int OFFSET_CENTER = 0;
    public static final int OFFSET_POS = 1;
    public static final int OFFSET_NEG = -1;
    public static final int UNSET_SLOT = -1;
    public static final long UNSET_TIME = -1L;
    public static final int PLOT_CELL_COUNT = 9;
    public static final int PLOT_RADIUS = 1;
    public static final int TOPO_A_STICKS_REQUIRED = 13;
    public static final int TOPO_B_STICKS_REQUIRED = 6;

    public static final int CHECK_INTERVAL_TICKS = 20;
    public static final int WORK_STEP_INTERVAL_TICKS = 10;
    public static final int NAV_REFRESH_TICKS = 20;
    public static final int BLOCK_BREAK_AUX_SFX = 2001;
    public static final int BLOCK_META_SHIFT = 12;
    public static final int BLOCK_UPDATE_FLAGS = 3;
    public static final int DEFAULT_DROP_FORTUNE = 0;
    public static final float FULL_DROP_CHANCE = 1.0F;
    public static final float ZERO_HARDNESS = 0.0F;
    public static final float HIT_CENTER = 0.5F;

    public static final double WORK_REACH_DIST_SQ = 16.0D;
    public static final double STAND_OFFSET_BLOCKS = 2.0D;
    public static final double WALK_SPEED = 0.6D;
    public static final double BLOCK_CENTER_OFFSET = 0.5D;
    public static final float LOOK_SPEED = 30.0F;
    public static final float DROP_OFFSET_Y = 0.2F;
    public static final double WORK_UTILITY = 1.0D;

    private BreedingWorkTuning() {}
}
