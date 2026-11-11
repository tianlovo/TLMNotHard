package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.MaidMainContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.BaubleContainer;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidMainLayout;

/** 原版页面的玩家先行槽号；复用当前背包/装备存储，不增加重复显示槽对应的第二份数据 */
public final class OriginalMaidTransfers {

    private OriginalMaidTransfers() {}

    public static ItemStack transfer(Container container, EntityPlayer player, int index) {
        if (!(container instanceof MaidMainContainer main) || index < 0 || index >= container.inventorySlots.size())
            return null;
        var slot = container.inventorySlots.get(index);
        if (!slot.getHasStack()) return null;
        var stack = slot.getStack();
        var before = stack.copy();
        if (index >= MaidMainLayout.PLAYER_END)
            merge(container, stack, MaidMainLayout.PLAYER_START, MaidMainLayout.PLAYER_END, true);
        else {
            boolean baublePage = container instanceof BaubleContainer;
            if (baublePage)
                merge(container, stack, MaidMainLayout.BACKPACK_START, container.inventorySlots.size(), false);
            if (stack.stackSize == before.stackSize || !baublePage) {
                merge(container, stack, MaidMainLayout.PLAYER_END, MaidMainLayout.ARMOR_END, false);
                if (stack.getItem() instanceof ItemBow || stack.getItem() instanceof ItemSword)
                    merge(container, stack, MaidMainLayout.ARMOR_END, MaidMainLayout.MAIN_HAND_END, false);
                if (!baublePage && stack.stackSize > 0) {
                    var remaining = main.getMaid()
                        .getMaidInventory()
                        .insertIntoBackpacks(stack, false);
                    stack.stackSize = remaining == null ? MaidMainLayout.EMPTY_STACK_COUNT : remaining.stackSize;
                    merge(container, stack, MaidMainLayout.BACKPACK_START, MaidMainLayout.BACKPACK_END, false);
                }
            }
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
