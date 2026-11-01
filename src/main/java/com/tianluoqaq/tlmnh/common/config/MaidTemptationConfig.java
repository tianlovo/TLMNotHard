package com.tianluoqaq.tlmnh.common.config;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

/** 原诱惑物的注册ID设置；目标平台没有对应的现代物品标签时回退蛋糕 */
public final class MaidTemptationConfig {

    public static String itemId = "minecraft:cake";

    private MaidTemptationConfig() {}

    public static void load(Configuration config) {
        itemId = config.getString(
            "MaidTemptationItem",
            "interaction",
            "minecraft:cake",
            "主手诱惑女仆的物品注册ID，未知ID回退蛋糕 / Main-hand temptation item ID; unknown ID falls back to cake");
    }

    public static boolean matches(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return false;
        Object configured = Item.itemRegistry.getObject(itemId);
        return stack.getItem() == (configured instanceof Item item ? item : Items.cake);
    }
}
