package com.tianluoqaq.tlmnh.compat;

import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/** 嵌套背包槽位两阶段合并与按条件取用工具：全程不改动入参实例，simulate=true 零原地修改 */
public final class BackpackSlotMergeHelper {

    public static final class MergeResult {

        public final ItemStack remaining;
        public final boolean changed;

        public MergeResult(ItemStack remaining, boolean changed) {
            this.remaining = remaining;
            this.changed = changed;
        }
    }

    public static final class ExtractResult {

        public final ItemStack extracted;
        public final boolean changed;

        public ExtractResult(ItemStack extracted, boolean changed) {
            this.extracted = extracted;
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

    public static ExtractResult extractFromArray(ItemStack[] slots, int slotLimit, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        if (slots == null || filter == null || maxCount <= 0) {
            return new ExtractResult(null, false);
        }
        int bound = Math.min(slots.length, slotLimit);
        if (scorer != null) {
            int bestIdx = CompatInventoryTuning.UNSET_SLOT;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < bound; i++) {
                ItemStack cur = slots[i];
                if (cur == null || cur.stackSize <= 0) continue;
                ItemStack probe = cur.copy();
                if (!filter.test(probe)) continue;
                int score = scorer.applyAsInt(probe);
                if (bestIdx < 0 || score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) return new ExtractResult(null, false);
            ItemStack chosen = slots[bestIdx];
            int take = Math.min(maxCount, chosen.stackSize);
            ItemStack out = chosen.copy();
            out.stackSize = take;
            if (!simulate) {
                if (chosen.stackSize <= take) {
                    slots[bestIdx] = null;
                } else {
                    ItemStack rem = chosen.copy();
                    rem.stackSize -= take;
                    slots[bestIdx] = rem;
                }
            }
            return new ExtractResult(out, true);
        }

        ItemStack out = null;
        int targetMax = maxCount;
        boolean changed = false;
        for (int i = 0; i < bound; i++) {
            ItemStack cur = slots[i];
            if (cur == null || cur.stackSize <= 0) continue;
            if (out == null) {
                if (!filter.test(cur.copy())) continue;
                targetMax = Math.min(maxCount, cur.getMaxStackSize());
                int take = Math.min(targetMax, cur.stackSize);
                out = cur.copy();
                out.stackSize = take;
                if (!simulate) {
                    if (cur.stackSize <= take) {
                        slots[i] = null;
                    } else {
                        ItemStack rem = cur.copy();
                        rem.stackSize -= take;
                        slots[i] = rem;
                    }
                }
                changed = true;
            } else if (canMergeStacks(out, cur)) {
                int take = Math.min(targetMax - out.stackSize, cur.stackSize);
                if (take <= 0) break;
                out.stackSize += take;
                if (!simulate) {
                    if (cur.stackSize <= take) {
                        slots[i] = null;
                    } else {
                        ItemStack rem = cur.copy();
                        rem.stackSize -= take;
                        slots[i] = rem;
                    }
                }
                changed = true;
            }
            if (out != null && out.stackSize >= targetMax) break;
        }
        return new ExtractResult(out, changed);
    }

    public static ExtractResult extractFromInventory(IInventory inv, int slotCount, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        Objects.requireNonNull(inv);
        if (filter == null || maxCount <= 0) {
            return new ExtractResult(null, false);
        }
        int bound = Math.min(inv.getSizeInventory(), slotCount);
        if (scorer != null) {
            int bestIdx = CompatInventoryTuning.UNSET_SLOT;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < bound; i++) {
                ItemStack cur = inv.getStackInSlot(i);
                if (cur == null || cur.stackSize <= 0) continue;
                ItemStack probe = cur.copy();
                if (!filter.test(probe)) continue;
                int score = scorer.applyAsInt(probe);
                if (bestIdx < 0 || score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) return new ExtractResult(null, false);
            ItemStack chosen = inv.getStackInSlot(bestIdx);
            int take = Math.min(maxCount, chosen.stackSize);
            ItemStack out = chosen.copy();
            out.stackSize = take;
            if (!simulate) {
                if (chosen.stackSize <= take) {
                    inv.setInventorySlotContents(bestIdx, null);
                } else {
                    ItemStack rem = chosen.copy();
                    rem.stackSize -= take;
                    inv.setInventorySlotContents(bestIdx, rem);
                }
            }
            return new ExtractResult(out, true);
        }

        ItemStack out = null;
        int targetMax = maxCount;
        boolean changed = false;
        for (int i = 0; i < bound; i++) {
            ItemStack cur = inv.getStackInSlot(i);
            if (cur == null || cur.stackSize <= 0) continue;
            if (out == null) {
                if (!filter.test(cur.copy())) continue;
                targetMax = Math.min(maxCount, cur.getMaxStackSize());
                int take = Math.min(targetMax, cur.stackSize);
                out = cur.copy();
                out.stackSize = take;
                if (!simulate) {
                    if (cur.stackSize <= take) {
                        inv.setInventorySlotContents(i, null);
                    } else {
                        ItemStack rem = cur.copy();
                        rem.stackSize -= take;
                        inv.setInventorySlotContents(i, rem);
                    }
                }
                changed = true;
            } else if (canMergeStacks(out, cur)) {
                int take = Math.min(targetMax - out.stackSize, cur.stackSize);
                if (take <= 0) break;
                out.stackSize += take;
                if (!simulate) {
                    if (cur.stackSize <= take) {
                        inv.setInventorySlotContents(i, null);
                    } else {
                        ItemStack rem = cur.copy();
                        rem.stackSize -= take;
                        inv.setInventorySlotContents(i, rem);
                    }
                }
                changed = true;
            }
            if (out != null && out.stackSize >= targetMax) break;
        }
        return new ExtractResult(out, changed);
    }
}
