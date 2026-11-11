package com.tianluoqaq.tlmnh.common.inventory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 服务端行动会话的整栈交换入口，不借用玩家权限，也不抢占他人的使用租约 */
public final class MaidEquipmentTransfer {

    public enum Result {

        EXCHANGED,
        REJECTED,
        FAILED;

        public boolean changed() {
            return this == EXCHANGED;
        }
    }

    public static final class Snapshot {

        private final EntityMaid owner;
        private final ItemStack[] references = new ItemStack[MaidInventory.TOTAL_SIZE];
        private final ItemStack[] contents = new ItemStack[MaidInventory.TOTAL_SIZE];

        private Snapshot(EntityMaid owner) {
            this.owner = owner;
            var inventory = owner.getMaidInventory();
            for (int slot = 0; slot < references.length; slot++) {
                references[slot] = inventory.getStackInSlot(slot);
                contents[slot] = ItemStack.copyItemStack(references[slot]);
            }
        }

        /** 仅供真实世界边界检查和能力提取，规划器只接收MaidWeaponProfile */
        public ItemStack stack(int slot) {
            if (slot < 0 || slot >= references.length) throw new IndexOutOfBoundsException("Maid slot: " + slot);
            return references[slot];
        }

        public boolean owned(MaidInventory inventory) {
            if (inventory != owner.getMaidInventory()) return false;
            for (int slot = 0; slot < references.length; slot++) {
                var actual = inventory.getStackInSlot(slot);
                if (actual != references[slot] || !ItemStack.areItemStacksEqual(actual, contents[slot])) return false;
            }
            return true;
        }

        private boolean hasAliases() {
            Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ItemStack stack : references) if (stack != null && !seen.add(stack)) return true;
            return false;
        }

        private boolean exchanged(MaidInventory inventory, int backpackSlot) {
            if (inventory != owner.getMaidInventory()) return false;
            for (int slot = 0; slot < references.length; slot++) {
                int before = slot == MaidInventory.MAIN_HAND ? backpackSlot
                    : slot == backpackSlot ? MaidInventory.MAIN_HAND : slot;
                var actual = inventory.getStackInSlot(slot);
                if (actual != references[before] || !ItemStack.areItemStacksEqual(actual, contents[before]))
                    return false;
            }
            return true;
        }
    }

    private static final String GUARD = "equipment/combat_transfer";
    private static final Set<EntityMaid> TRANSFERRING = Collections.newSetFromMap(new IdentityHashMap<>());

    private MaidEquipmentTransfer() {}

    public static Snapshot capture(EntityMaid maid) {
        if (!serverEntity(maid) || !IntegrationGuard.available(Side.SERVER, GUARD)) return null;
        try {
            var snapshot = new Snapshot(maid);
            return !snapshot.hasAliases() && snapshot.owned(maid.getMaidInventory()) ? snapshot : null;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            return null;
        }
    }

    /** 调用方先取消自己持有的弓等租约，再取快照；其他租约仍存在时直接拒绝 */
    public static Result exchange(EntityMaid maid, Snapshot expected, int backpackSlot, BooleanSupplier authorized) {
        if (!serverEntity(maid) || expected == null
            || expected.owner != maid
            || authorized == null
            || backpackSlot < 0
            || backpackSlot >= MaidInventory.BACKPACK_SIZE
            || expected.stack(backpackSlot) == null
            || !IntegrationGuard.available(Side.SERVER, GUARD)
            || !TRANSFERRING.add(maid)) return Result.REJECTED;
        var inventory = maid.getMaidInventory();
        try {
            if (maid.getCombatHitReceipt()
                .blocksTransfer()
                || maid.getItemUseState()
                    .peek() != null
                || !authorized.getAsBoolean()
                || !serverEntity(maid)
                || maid.getCombatHitReceipt()
                    .blocksTransfer()
                || maid.getItemUseState()
                    .peek() != null
                || !expected.owned(inventory)) return Result.REJECTED;
            boolean changed = inventory.exchangeBackpackWithMainHand(
                backpackSlot,
                expected.stack(backpackSlot),
                expected.stack(MaidInventory.MAIN_HAND));
            if (changed && expected.exchanged(inventory, backpackSlot)) return Result.EXCHANGED;
            if (!changed && expected.owned(inventory)) return Result.REJECTED;
            throw new IllegalStateException("Equipment exchange result cannot be proven");
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            // 同步投影失败时实物可能已经完成交换，确认原栈的完整后继而不回滚或重试
            if (expected.exchanged(inventory, backpackSlot)) {
                maid.getEquipmentEpoch()
                    .invalidate();
                return Result.EXCHANGED;
            }
            return Result.FAILED;
        } finally {
            TRANSFERRING.remove(maid);
        }
    }

    private static boolean serverEntity(EntityMaid maid) {
        return maid != null && maid.worldObj != null
            && !maid.worldObj.isRemote
            && maid.isEntityAlive()
            && !MaidLifecycleService.isPendingRedemption(maid)
            && maid.worldObj.getEntityByID(maid.getEntityId()) == maid;
    }
}
