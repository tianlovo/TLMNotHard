package com.tianluoqaq.tlmnh.common.inventory;

/** 女仆物品栏槽位索引、容量与转移常量（Layout） */
public final class MaidInventoryLayout {

    public static final int BACKPACK_SIZE = 36;
    public static final int BACKPACK_SHOW_SLOT = 5;
    public static final int MAIN_HAND = BACKPACK_SIZE;
    public static final int OFF_HAND = MAIN_HAND + 1;
    public static final int ARMOR_START = OFF_HAND + 1;
    public static final int TOTAL_SIZE = ARMOR_START + 4;
    public static final int TARGET_GET_ID_THRESHOLD = 3;
    public static final int SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE = 0;
    public static final int TARGET_LENGTH_THRESHOLD = 5;
    public static final int INVENTORY_GET_INVENTORY_STACK_LIMIT_PARAM = 64;
    public static final int MIN_PASS_THRESHOLD = 2;
    public static final int TARGET_PASS_THRESHOLD = 1;
    public static final double INVENTORY_IS_USEABLE_BY_PLAYER_FACTOR = 64.0D;
    public static final int UNSIGNED_BYTE_MASK = 255;
    public static final double HALF_FACTOR_D = 0.5;
    public static final int TARGET_SIZE_THRESHOLD = 48;
    public static final int MAX_INDEX_THRESHOLD = 36;
    public static final int ORIGINAL_TRANSFERS_MERGE_FOURTH_PARAM = 40;
    public static final int ORIGINAL_TRANSFERS_MERGE_FOURTH_PARAM_2 = 41;
    public static final int ORIGINAL_TRANSFERS_MERGE_THIRD_PARAM = 42;

    private MaidInventoryLayout() {}
}
