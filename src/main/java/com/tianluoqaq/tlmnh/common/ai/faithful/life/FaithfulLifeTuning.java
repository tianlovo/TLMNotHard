package com.tianluoqaq.tlmnh.common.ai.faithful.life;

/** 原版对齐模式起居与睡眠作息参数（Tuning） */
public final class FaithfulLifeTuning {

    public static final int MISSING_CAKE_SLOT = -1;

    public static final int BED_EXIT_DEFAULT_ZERO_BASE = 0;
    public static final int BED_EXIT_DEFAULT_UNIT_STEP = 1;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int LOW_TWO_BITS_MASK = 3;
    public static final int BED_EXIT_SIDE = 2;
    public static final int BED_EXIT_ORDERED_PARAM = -2;
    public static final int CAKE_ACTION_CONSUME_PARAM = 8;
    public static final double CAKE_ACTION_CONSUME_FACTOR = .1;
    public static final double CAKE_ACTION_CONSUME_FACTOR_2 = .02;
    public static final float CAKE_ACTION_TRY_PLACE_ITEM_INTO_WORLD_ARG7_FACTOR = .5F;
    public static final double MODERN_BLOCK_SOLID_FACTOR = .7291666666666666D;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final double MODERN_LIFE_SET_POSITION_AND_UPDATE_FIRST_FACTOR = .5;
    public static final double MODERN_LIFE_SET_POSITION_AND_UPDATE_SECOND_FACTOR = .8;
    public static final int MODERN_LIFE_MAINTAIN_PARAM = 16;
    public static final double MODERN_LIFE_SET_POSITION_SECOND_FACTOR = .5625;
    public static final float MODERN_LIFE_ANGLE = 0.017453292F;

    private FaithfulLifeTuning() {}
}
