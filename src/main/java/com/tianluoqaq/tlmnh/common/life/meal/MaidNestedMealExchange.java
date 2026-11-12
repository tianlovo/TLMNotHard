package com.tianluoqaq.tlmnh.common.life.meal;

import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;

/** 满裸槽时借用背包内食物；被换下的手持物始终保存在同一真实背包内 */
final class MaidNestedMealExchange {

    final int handSlot;
    final ItemStack food;
    private final ItemStack container;
    private final ItemStack displaced;

    private MaidNestedMealExchange(int handSlot, ItemStack food, ItemStack container, ItemStack displaced) {
        this.handSlot = handSlot;
        this.food = food;
        this.container = container;
        this.displaced = displaced == null ? null : displaced.copy();
    }

    static MaidNestedMealExchange open(EntityMaid maid, Predicate<ItemStack> filter) {
        if (maid.getCombatHitReceipt()
            .blocksTransfer()) return null;
        var inv = maid.getMaidInventory();
        var provider = CompatServices.backpacks();
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            var bag = inv.getStackInSlot(slot);
            if (!provider.isSupportedBackpack(bag)) continue;
            int hand = inv.getStackInSlot(MaidInventoryLayout.MAIN_HAND) == null ? MaidInventoryLayout.MAIN_HAND
                : MaidInventoryLayout.OFF_HAND;
            if (hand == slot) hand = hand == MaidInventoryLayout.MAIN_HAND ? MaidInventoryLayout.OFF_HAND
                : MaidInventoryLayout.MAIN_HAND;
            var displaced = inv.getStackInSlot(hand);
            if (displaced != null && provider.isSupportedBackpack(displaced)) continue;
            var food = provider.extractFromBackpack(bag, filter, null, inv.getInventoryStackLimit(), false);
            if (food == null) continue;
            if (displaced != null) {
                var remainder = provider.insertIntoBackpack(bag, displaced, true);
                if (remainder != null) {
                    restoreStack(maid, bag, food);
                    continue;
                }
                remainder = provider.insertIntoBackpack(bag, displaced, false);
                if (remainder != null) {
                    // Preserve every owned stack even if a provider refuses its successful simulation.
                    inv.setInventorySlotContents(hand, remainder);
                    restoreStack(maid, bag, food);
                    continue;
                }
            }
            inv.setInventorySlotContents(hand, food);
            return new MaidNestedMealExchange(hand, inv.getStackInSlot(hand), bag, displaced);
        }
        return null;
    }

    void restore(EntityMaid maid) {
        var inv = maid.getMaidInventory();
        var held = inv.getStackInSlot(handSlot);
        if (held != food && !(held == null && food.stackSize <= 0)) return;
        boolean bagOwned = false;
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++)
            bagOwned |= inv.getStackInSlot(slot) == container;
        if (!bagOwned) return;
        var provider = CompatServices.backpacks();
        ItemStack returned = null;
        if (displaced != null) {
            returned = provider.extractFromBackpack(
                container,
                stack -> displaced.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(displaced, stack),
                null,
                displaced.stackSize,
                false);
            if (returned == null) return;
            if (returned.stackSize != displaced.stackSize) {
                restoreStack(maid, container, returned);
                return;
            }
        }
        var remainder = held == null || held.stackSize <= 0 ? null
            : provider.insertIntoBackpack(container, held, false);
        if (remainder == null) inv.setInventorySlotContents(handSlot, returned);
        else {
            inv.setInventorySlotContents(handSlot, remainder);
            if (returned != null) restoreStack(maid, container, returned);
        }
        inv.markDirty();
    }

    private static void restoreStack(EntityMaid maid, ItemStack container, ItemStack stack) {
        var remainder = CompatServices.backpacks()
            .insertIntoBackpack(container, stack, false);
        if (remainder != null) remainder = maid.getMaidInventory()
            .insertAvailable(remainder, false);
        if (remainder != null) maid.entityDropItem(remainder, MaidMealTuning.FOOD_PROFILE_INSPECT_SAFE_ZERO_BASE);
    }
}
