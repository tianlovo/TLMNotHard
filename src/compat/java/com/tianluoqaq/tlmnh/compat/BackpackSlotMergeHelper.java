package com.tianluoqaq.tlmnh.compat;

import java.util.Objects;
import java.util.function.IntPredicate;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/** 嵌套背包槽位两阶段合并工具：先合并已有同类非满堆叠，再占空槽，全程不改动入参实例 */
public final class BackpackSlotMergeHelper {

    public static final class MergeResult {

        public final ItemStack remaining;
        public final boolean changed;

        public MergeResult(ItemStack remaining, boolean changed) {
            this.remaining = remaining;
            this.changed = changed;
        }
    }

    private BackpackSlotMergeHelper() {}

    public static boolean canMergeStacks(ItemStack existing, ItemStack incoming) {
        if (existing == null || incoming == null) return false;
        if (existing.getItem() != incoming.getItem()) return false;
        if (existing.getItemDamage() != incoming.getItemDamage()) return false;
        return ItemStack.areItemStackTagsEqual(existing, incoming);
    }

    public static MergeResult mergeIntoArray(ItemStack[] slots, int slotLimit, int maxStackLimit, ItemStack input,
        boolean simulate) {
        if (input == null || input.stackSize <= 0) {
            return new MergeResult(null, false);
        }
        int bound = Math.min(slots.length, slotLimit);
        int effectiveMax = Math.min(input.getMaxStackSize(), maxStackLimit);
        if (effectiveMax <= 0) {
            return new MergeResult(input.copy(), false);
        }
        ItemStack rem = input.copy();
        boolean changed = false;

        for (int i = 0; i < bound && rem.stackSize > 0; i++) {
            ItemStack cur = slots[i];
            if (cur == null || cur.stackSize <= 0 || !canMergeStacks(cur, rem)) continue;
            int cap = Math.min(cur.getMaxStackSize(), maxStackLimit) - cur.stackSize;
            if (cap <= 0) continue;
            int move = Math.min(cap, rem.stackSize);
            if (!simulate) {
                ItemStack updated = cur.copy();
                updated.stackSize += move;
                slots[i] = updated;
            }
            rem.stackSize -= move;
            changed = true;
        }

        for (int i = 0; i < bound && rem.stackSize > 0; i++) {
            ItemStack cur = slots[i];
            if (cur != null && cur.stackSize > 0) continue;
            int move = Math.min(effectiveMax, rem.stackSize);
            if (!simulate) {
                ItemStack placed = rem.copy();
                placed.stackSize = move;
                slots[i] = placed;
            }
            rem.stackSize -= move;
            changed = true;
        }

        return new MergeResult(rem.stackSize <= 0 ? null : rem, changed);
    }

    public static MergeResult mergeIntoInventory(IInventory inv, int slotCount, ItemStack input,
        IntPredicate slotValidator, boolean simulate) {
        Objects.requireNonNull(inv);
        if (input == null || input.stackSize <= 0) {
            return new MergeResult(null, false);
        }
        int bound = Math.min(inv.getSizeInventory(), slotCount);
        int invLimit = inv.getInventoryStackLimit();
        int effectiveMax = Math.min(input.getMaxStackSize(), invLimit);
        if (effectiveMax <= 0) {
            return new MergeResult(input.copy(), false);
        }
        ItemStack rem = input.copy();
        boolean changed = false;

        for (int i = 0; i < bound && rem.stackSize > 0; i++) {
            if (slotValidator != null && !slotValidator.test(i)) continue;
            ItemStack cur = inv.getStackInSlot(i);
            if (cur == null || cur.stackSize <= 0 || !canMergeStacks(cur, rem)) continue;
            int cap = Math.min(cur.getMaxStackSize(), invLimit) - cur.stackSize;
            if (cap <= 0) continue;
            int move = Math.min(cap, rem.stackSize);
            if (!simulate) {
                ItemStack updated = cur.copy();
                updated.stackSize += move;
                inv.setInventorySlotContents(i, updated);
            }
            rem.stackSize -= move;
            changed = true;
        }

        for (int i = 0; i < bound && rem.stackSize > 0; i++) {
            if (slotValidator != null && !slotValidator.test(i)) continue;
            ItemStack cur = inv.getStackInSlot(i);
            if (cur != null && cur.stackSize > 0) continue;
            int move = Math.min(effectiveMax, rem.stackSize);
            if (!simulate) {
                ItemStack placed = rem.copy();
                placed.stackSize = move;
                inv.setInventorySlotContents(i, placed);
            }
            rem.stackSize -= move;
            changed = true;
        }

        return new MergeResult(rem.stackSize <= 0 ? null : rem, changed);
    }
}
