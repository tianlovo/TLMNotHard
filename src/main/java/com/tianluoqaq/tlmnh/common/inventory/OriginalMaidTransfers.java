package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/** 原版页面的玩家先行槽号；复用当前背包/装备存储，不增加重复显示槽对应的第二份数据 */
public final class OriginalMaidTransfers {

    private OriginalMaidTransfers() {}

    public static ItemStack transfer(Container container, EntityPlayer player, int index) {
        if (index < 0 || index >= container.inventorySlots.size()
            || container.inventorySlots.size() != MaidInventoryLayout.TARGET_SIZE_THRESHOLD) return null;
        var slot = container.inventorySlots.get(index);
        if (!slot.getHasStack()) return null;
        var stack = slot.getStack();
        var before = stack.copy();
        if (index >= MaidInventoryLayout.MAX_INDEX_THRESHOLD) merge(
            container,
            stack,
            MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE,
            MaidInventoryLayout.MAX_INDEX_THRESHOLD,
            true);
        else {
            merge(
                container,
                stack,
                MaidInventoryLayout.MAX_INDEX_THRESHOLD,
                MaidInventoryLayout.ORIGINAL_TRANSFERS_MERGE_FOURTH_PARAM,
                false);
            if (stack.getItem() instanceof ItemBow || stack.getItem() instanceof ItemSword) merge(
                container,
                stack,
                MaidInventoryLayout.ORIGINAL_TRANSFERS_MERGE_FOURTH_PARAM,
                MaidInventoryLayout.ORIGINAL_TRANSFERS_MERGE_FOURTH_PARAM_2,
                false);
            merge(
                container,
                stack,
                MaidInventoryLayout.ORIGINAL_TRANSFERS_MERGE_THIRD_PARAM,
                MaidInventoryLayout.TARGET_SIZE_THRESHOLD,
                false);
        }
        if (stack.stackSize == before.stackSize) return null;
        if (stack.stackSize <= 0) slot.putStack(null);
        else slot.onSlotChanged();
        slot.onPickupFromSlot(player, stack);
        return before;
    }

    private static void merge(Container container, ItemStack source, int start, int end, boolean reverse) {
        for (int pass = 0; pass < MaidInventoryLayout.MIN_PASS_THRESHOLD && source.stackSize > 0; pass++)
            for (int offset = 0; offset < end - start && source.stackSize > 0; offset++) {
                var slot = container.inventorySlots.get(reverse ? end - 1 - offset : start + offset);
                if (!slot.isItemValid(source)) continue;
                var existing = slot.getStack();
                int limit = Math.min(slot.getSlotStackLimit(), source.getMaxStackSize());
                if (pass == 0 && existing != null
                    && source.isStackable()
                    && source.isItemEqual(existing)
                    && ItemStack.areItemStackTagsEqual(source, existing)) {
                    int moved = Math.min(
                        source.stackSize,
                        Math.max(
                            MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE,
                            limit - existing.stackSize));
                    if (moved > 0) {
                        existing.stackSize += moved;
                        source.stackSize -= moved;
                        slot.onSlotChanged();
                    }
                } else if (pass == MaidInventoryLayout.TARGET_PASS_THRESHOLD && existing == null && limit > 0)
                    slot.putStack(source.splitStack(Math.min(limit, source.stackSize)));
            }
    }
}
