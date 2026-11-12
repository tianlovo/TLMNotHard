package com.tianluoqaq.tlmnh.common.life;

import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;

/** 生存物资的统一取用入口，裸槽与允许取用的背包内部使用同一过滤条件 */
public final class MaidSurvivalInventory {

    private MaidSurvivalInventory() {}

    public static ItemStack extract(EntityMaid maid, Predicate<ItemStack> filter, int count, boolean simulate) {
        if (maid.getCombatHitReceipt()
            .blocksTransfer()) return null;
        var inv = maid.getMaidInventory();
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            var stack = inv.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0 || !filter.test(stack.copy())) continue;
            var single = stack.copy();
            single.stackSize = Math.min(count, stack.stackSize);
            if (!simulate) inv.decrStackSize(slot, single.stackSize);
            return single;
        }
        var provider = CompatServices.backpacks();
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            var container = inv.getStackInSlot(slot);
            var result = provider.extractFromBackpack(container, filter, null, count, simulate);
            if (result != null && result.stackSize > 0) {
                if (!simulate) inv.markDirty();
                return result;
            }
        }
        return null;
    }

    public static int unpackToFreeSlot(EntityMaid maid, Predicate<ItemStack> filter) {
        var inv = maid.getMaidInventory();
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            if (inv.getStackInSlot(slot) != null) continue;
            var extracted = extract(maid, filter, MaidSurvivalTuning.SINGLE_ITEM, false);
            if (extracted == null) return MaidSurvivalTuning.UNSET_SLOT;
            inv.setInventorySlotContents(slot, extracted);
            return slot;
        }
        return MaidSurvivalTuning.UNSET_SLOT;
    }
}
