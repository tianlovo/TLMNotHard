package com.tianluoqaq.tlmnh.client.render.attachment;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/** 原LayerMaidBackItem的Vanishable入口，补足旧平台没有该标记的原生指南针 */
public final class MaidBackItemKinds {

    private MaidBackItemKinds() {}

    public static boolean displaysOnBack(ItemStack stack) {
        // 1.20.1 CompassItem implements Vanishable，但指南针没有耐久；未知无耐久物品不据此扩大
        return stack != null && stack.stackSize > 0
            && stack.getItem() != null
            && (stack.getMaxDamage() > 0 || stack.getItem() == Items.compass);
    }
}
