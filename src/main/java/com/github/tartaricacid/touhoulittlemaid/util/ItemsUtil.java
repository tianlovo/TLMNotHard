/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: util/ItemsUtil.java findStackSlot, MIT
 * Adaptation: current maid backpack inventory and callback authorization
 */
package com.github.tartaricacid.touhoulittlemaid.util;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

public final class ItemsUtil {

    private ItemsUtil() {}

    public static int findStackSlot(EntityMaid maid, Predicate<ItemStack> filter, int maxCount,
        BooleanSupplier current) {
        if (!current.getAsBoolean()) return -1;
        int slot = find(maid, filter);
        if (slot >= 0 || maid.worldObj.isRemote) return slot;
        if (!current.getAsBoolean()) return -1;
        var event = new MaidRequestItemEvent(maid, filter, maxCount);
        MinecraftForge.EVENT_BUS.post(event);
        if (!current.getAsBoolean() || !event.hasRequestedItem()) return -1;
        return find(maid, filter);
    }

    private static int find(EntityMaid maid, Predicate<ItemStack> filter) {
        for (int i = 0; i < MaidInventory.BACKPACK_SIZE; i++) if (filter.test(
            maid.getMaidInventory()
                .getStackInSlot(i)))
            return i;
        return -1;
    }
}
