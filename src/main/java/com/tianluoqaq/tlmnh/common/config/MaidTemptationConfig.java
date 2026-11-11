package com.tianluoqaq.tlmnh.common.config;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

/** 原诱惑物的注册ID设置；目标平台没有对应的现代物品标签时回退蛋糕 */
public final class MaidTemptationConfig {

    public static String itemId = MaidConfigConfigSpec.ID_MINECRAFT_CAKE;

    private MaidTemptationConfig() {}

    public static void load(Configuration config) {
        itemId = config.getString(
            MaidConfigConfigSpec.KEY_MAID_TEMPTATION_ITEM,
            MaidConfigConfigSpec.ID_INTERACTION,
            MaidConfigConfigSpec.ID_MINECRAFT_CAKE,
            MaidConfigConfigSpec.MSG_ID_ID_MAIN_HAND_TEMPTATION);
    }

    public static boolean matches(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return false;
        Object configured = Item.itemRegistry.getObject(itemId);
        return stack.getItem() == (configured instanceof Item item ? item : Items.cake);
    }
}
