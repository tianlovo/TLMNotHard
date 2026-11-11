package com.tianluoqaq.tlmnh.common.life.bed;

import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;

/** 固定上游七种床色；保存现代DyeColor ID，染料入口映射旧版ItemDye的反向编号 */
public enum MaidBedColor {

    WHITE(MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE, MaidBedIds.ID_WHITE),
    BLACK(MaidBedLayout.BED_COLOR_BLACK_FIRST_PARAM, MaidBedIds.ID_BLACK),
    YELLOW(MaidBedLayout.BED_COLOR_YELLOW_FIRST_PARAM, MaidBedIds.ID_YELLOW),
    BLUE(MaidBedLayout.BED_COLOR_BLUE_FIRST_PARAM, MaidBedIds.ID_BLUE),
    GREEN(MaidBedLayout.BED_COLOR_GREEN_FIRST_PARAM, MaidBedIds.ID_GREEN),
    PURPLE(MaidBedLayout.BED_COLOR_PURPLE_FIRST_PARAM, MaidBedIds.ID_PURPLE),
    PINK(MaidBedLayout.BED_COLOR_PINK_FIRST_PARAM, MaidBedIds.ID_PINK);

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
        return damage >= 0 && damage < MaidBedLayout.MIN_DAMAGE_THRESHOLD
            ? byId(MaidBedLayout.BED_COLOR_BLACK_FIRST_PARAM - damage)
            : null;
    }
}
