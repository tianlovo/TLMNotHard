package com.tianluoqaq.tlmnh.common.inventory.bauble;

/** 饰品容量、好感度开放档位与原版界面槽位坐标 */
public final class MaidBaubleLayout {

    public static final int SIZE = 30;
    public static final int COLUMNS = 5;
    public static final int FIRST_TIER_SIZE = 10;
    public static final int SECOND_TIER_SIZE = 20;
    public static final int SECOND_TIER_LEVEL = 2;
    public static final int FINAL_TIER_LEVEL = 3;
    public static final int SLOT_X = 152;
    public static final int SLOT_Y = 45;
    public static final int CELL_STRIDE = 18;
    public static final int STACK_LIMIT = 64;
    public static final int UNSIGNED_BYTE_MASK = 255;

    private MaidBaubleLayout() {}

    public static int unlockedSlots(int level) {
        if (level >= FINAL_TIER_LEVEL) return SIZE;
        return level >= SECOND_TIER_LEVEL ? SECOND_TIER_SIZE : FIRST_TIER_SIZE;
    }
}
