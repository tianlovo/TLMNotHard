package com.tianluoqaq.tlmnh.common.life.bed;

import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;

/** 固定上游七种床色；保存现代DyeColor ID，染料入口映射旧版ItemDye的反向编号 */
public enum MaidBedColor {

    WHITE(0, "white"),
    BLACK(15, "black"),
    YELLOW(4, "yellow"),
    BLUE(11, "blue"),
    GREEN(13, "green"),
    PURPLE(10, "purple"),
    PINK(6, "pink");

    public final int id;
    public final String resourceName;

    MaidBedColor(int id, String resourceName) {
        this.id = id;
        this.resourceName = resourceName;
    }

    public static MaidBedColor byId(int id) {
        for (var color : values()) if (color.id == id) return color;
        return null;
    }

    public static MaidBedColor fromDye(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemDye)) return null;
        int damage = stack.getItemDamage();
        return damage >= 0 && damage < 16 ? byId(15 - damage) : null;
    }
}
