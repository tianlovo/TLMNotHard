package com.tianluoqaq.tlmnh.common.life.cabinet;

/** 零食柜方块类型、容量与持久化常量（Layout） */
public final class SnackCabinetLayout {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_FULL = 1;
    public static final int TYPE_HALF = 2;
    public static final int RECHECK_TICKS = 5;
    public static final float BLOCK_SNACK_SET_HARDNESS_FIRST_FACTOR = 2.0F;
    public static final float BLOCK_SNACK_SET_RESISTANCE_FIRST_FACTOR = 3.0F;
    public static final int LOW_TWO_BITS_MASK = 3;
    public static final int BLOCK_SNACK_FACING_DIRECTION_PARAM = 2;
    public static final float BLOCK_SNACK_FACING = 4.0F;
    public static final float FULL_TURN_DEGREES_F = 360.0F;
    public static final double BLOCK_SNACK_FACING_2 = .5;
    public static final int BLOCK_SNACK_BIT_MASK_12 = 12;
    public static final int BLOCK_SNACK_GET_COMPARATOR_INPUT_OVERRIDE_ZERO_BASE = 0;
    public static final int TARGET_SIDE_THRESHOLD = 1;
    public static final int CABINET_OPENERS_TICK_PARAM = 5;
    public static final int CABINET_OPENERS_TICK_PARAM_2 = 6;
    public static final double CABINET_OPENERS_TICK_FACTOR = -.5;
    public static final float CABINET_OPENERS_TICK_FACTOR_2 = .5F;
    public static final float CABINET_OPENERS_TICK_FACTOR_3 = .1F;
    public static final float CABINET_OPENERS_TICK_FACTOR_4 = .8F;
    public static final int TILE_ENTITY_DOMAIN_PARAM = 27;
    public static final int TILE_ENTITY_GET_INVENTORY_STACK_LIMIT_PARAM = 64;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final int UNSIGNED_BYTE_MASK = 255;

    private SnackCabinetLayout() {}
}
