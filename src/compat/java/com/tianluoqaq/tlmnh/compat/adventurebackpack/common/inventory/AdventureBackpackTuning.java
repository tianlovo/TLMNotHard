package com.tianluoqaq.tlmnh.compat.adventurebackpack.common.inventory;

/** 冒险者背包通用存储槽边界（48..53 为专属工具槽与流体桶槽，不参与普通物品塞入） */
public final class AdventureBackpackTuning {

    public static final int STORAGE_SLOT_COUNT = 48;
    public static final int NBT_COMPOUND_TAG_TYPE = 10;
    public static final int UNSIGNED_BYTE_MASK = 0xFF;

    private AdventureBackpackTuning() {}
}
