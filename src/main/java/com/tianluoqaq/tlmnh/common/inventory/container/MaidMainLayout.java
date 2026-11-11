package com.tianluoqaq.tlmnh.common.inventory.container;

import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;

/** 原版女仆页面的玩家槽、装备槽和六行背包布局 */
public final class MaidMainLayout {

    public static final int PLAYER_START = 0;
    public static final int EMPTY_STACK_COUNT = 0;
    public static final int PLAYER_END = 36;
    public static final int ARMOR_END = 40;
    public static final int MAIN_HAND_END = 41;
    public static final int BACKPACK_START = 42;
    public static final int BACKPACK_END = BACKPACK_START + MaidInventoryLayout.BACKPACK_SIZE;
    public static final int BACKPACK_COLUMNS = 6;
    public static final int BACKPACK_X = 143;
    public static final int CELL_STRIDE = 18;
    private static final int[] BACKPACK_ROW_Y = { 37, 59, 82, 100, 123, 141 };

    private MaidMainLayout() {}

    public static int backpackY(int row) {
        return BACKPACK_ROW_Y[row];
    }
}
