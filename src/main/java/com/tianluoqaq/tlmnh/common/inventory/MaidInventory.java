package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryStore;

/** 六格背包、双手与护甲的统一槽位视图，装备仍只有实体中的一份存储 */
public final class MaidInventory implements IInventory {

    private final EntityMaid maid;
    private final RecoveryStore recovery;

    public RecoveryStore recovery() {
        return recovery;
    }

    private final ItemStack[] backpack = new ItemStack[MaidInventoryLayout.BACKPACK_SIZE];

    public MaidInventory(EntityMaid maid) {
        this.maid = maid;
        recovery = new RecoveryStore(maid);
    }

    @Override
    public int getSizeInventory() {
        return MaidInventoryLayout.TOTAL_SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        checkSlot(slot);
        if (slot < MaidInventoryLayout.BACKPACK_SIZE) return backpack[slot];
        if (slot == MaidInventoryLayout.MAIN_HAND) return maid.getHeldItem();
        if (slot == MaidInventoryLayout.OFF_HAND) return maid.getOffhandItem();
        return maid.getEquipmentInSlot(slot - MaidInventoryLayout.ARMOR_START + 1);
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        var stack = getStackInSlot(slot);
        if (stack == null || count <= 0) return null;
        ItemStack removed;
        if (stack.stackSize <= count) {
            removed = stack;
            setInventorySlotContents(slot, null);
        } else {
            removed = stack.splitStack(count);
            markDirty();
        }
        return removed;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        var stack = getStackInSlot(slot);
        setInventorySlotContents(slot, null);
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        checkSlot(slot);
        stack = ProtectedItemCodec.admit(stack, getInventoryStackLimit());
        if (slot < MaidInventoryLayout.BACKPACK_SIZE) backpack[slot] = stack;
        else if (slot == MaidInventoryLayout.MAIN_HAND)
            maid.setCurrentItemOrArmor(MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE, stack);
        else if (slot == MaidInventoryLayout.OFF_HAND) maid.setOffhandItem(stack);
        else maid.setCurrentItemOrArmor(slot - MaidInventoryLayout.ARMOR_START + 1, stack);
        markDirty();
    }

    /** 已接纳物品的内部移位，不重新序列化/接纳而触发外部物品回调 */
    boolean exchangeBackpackWithMainHand(int slot, ItemStack expectedBackpack, ItemStack expectedHand) {
        if (maid.worldObj == null || maid.worldObj.isRemote
            || maid.getCombatHitReceipt()
                .blocksTransfer()
            || slot < 0
            || slot >= MaidInventoryLayout.BACKPACK_SIZE
            || expectedBackpack == null
            || expectedBackpack == expectedHand
            || backpack[slot] != expectedBackpack
            || maid.getHeldItem() != expectedHand) return false;
        var equipment = maid.getLastActiveItems();
        if (equipment == null || equipment.length != MaidInventoryLayout.TARGET_LENGTH_THRESHOLD
            || equipment[MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE] != expectedHand) return false;
        for (int other = 0; other < MaidInventoryLayout.TOTAL_SIZE; other++) {
            if (other == slot || other == MaidInventoryLayout.MAIN_HAND) continue;
            var actual = getStackInSlot(other);
            if (actual == expectedBackpack || expectedHand != null && actual == expectedHand) return false;
        }
        // 原生EntityLiving.setCurrentItemOrArmor只写同一equipment数组；两份原物品不复制、不扣数量
        equipment[MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE] = expectedBackpack;
        if (maid.getHeldItem() != expectedBackpack) {
            // 未知提供方若返回装备副本，不撤走原背包物品
            if (equipment[MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE] == expectedBackpack)
                equipment[MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE] = expectedHand;
            return false;
        }
        backpack[slot] = expectedHand;
        maid.getEquipmentEpoch()
            .invalidate();
        markDirty();
        return true;
    }

    @Override
    public String getInventoryName() {
        return MaidInventoryIds.I18N_CONTAINER_TLMNH_MAID;
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return MaidInventoryLayout.INVENTORY_GET_INVENTORY_STACK_LIMIT_PARAM;
    }

    @Override
    public void markDirty() {
        // DataWatcher 比较副本，捕获使用道具时对副手 ItemStack 的原地修改
        maid.syncOffhand();
        maid.syncBackpackShowItem();
    }

    /** 按上游顺序合并背包和双手，再占空位；不自动穿甲，不修改输入物品 */
    public ItemStack insertAvailable(ItemStack input, boolean simulate) {
        if (input == null || input.getItem() == null || input.stackSize <= 0) return null;
        var remaining = input.copy();
        for (int pass = 0; pass < MaidInventoryLayout.MIN_PASS_THRESHOLD && remaining.stackSize > 0; pass++) {
            for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND && remaining.stackSize > 0; slot++) {
                var existing = getStackInSlot(slot);
                int limit = Math.min(getInventoryStackLimit(), remaining.getMaxStackSize());
                int capacity;
                if (pass == 0 && existing != null
                    && remaining.isStackable()
                    && remaining.isItemEqual(existing)
                    && ItemStack.areItemStackTagsEqual(remaining, existing))
                    capacity = Math
                        .max(MaidInventoryLayout.SET_CURRENT_ITEM_OR_ARMOR_INITIAL_VALUE, limit - existing.stackSize);
                else if (pass == MaidInventoryLayout.TARGET_PASS_THRESHOLD && existing == null) capacity = limit;
                else continue;
                int moved = Math.min(capacity, remaining.stackSize);
                if (!simulate && moved > 0) {
                    if (existing == null) {
                        var inserted = remaining.copy();
                        inserted.stackSize = moved;
                        setInventorySlotContents(slot, inserted);
                    } else existing.stackSize += moved;
                }
                remaining.stackSize -= moved;
            }
        }
        if (!simulate && remaining.stackSize != input.stackSize) markDirty();
        return remaining.stackSize > 0 ? remaining : null;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return maid.isEntityAlive() && player != null
            && player.isEntityAlive()
            && maid.isOwner(player)
            && player.worldObj == maid.worldObj
            && player.getDistanceSqToEntity(maid) <= MaidInventoryLayout.INVENTORY_IS_USEABLE_BY_PLAYER_FACTOR;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        checkSlot(slot);
        if (stack == null) return false;
        if (slot < MaidInventoryLayout.ARMOR_START) return true;
        return stack.getItem()
            .isValidArmor(
                stack,
                MaidInventoryLayout.TARGET_GET_ID_THRESHOLD - (slot - MaidInventoryLayout.ARMOR_START),
                maid);
    }

    public NBTTagList writeBackpack() {
        var items = new NBTTagList();
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++) {
            if (backpack[slot] != null) {
                var item = ProtectedItemCodec.encode(backpack[slot]);
                item.setByte(MaidInventoryNbtKeys.KEY_SLOT, (byte) slot);
                items.appendTag(item);
            }
        }
        return items;
    }

    public void readBackpack(NBTTagList items) {
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++) backpack[slot] = null;
        for (int index = 0; index < items.tagCount(); index++) {
            var item = items.getCompoundTagAt(index);
            int slot = item.getByte(MaidInventoryNbtKeys.KEY_SLOT) & MaidInventoryLayout.UNSIGNED_BYTE_MASK;
            if (!item.hasKey(MaidInventoryNbtKeys.KEY_SLOT, Constants.NBT.TAG_BYTE)
                || slot >= MaidInventoryLayout.BACKPACK_SIZE
                || backpack[slot] != null) recovery.retain(item);
            else {
                var stack = ProtectedItemCodec.decode(item);
                backpack[slot] = stack == null ? ProtectedItemCodec.seal(item, MaidInventoryIds.ID_EMPTY_ENTRY) : stack;
            }
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= MaidInventoryLayout.TOTAL_SIZE)
            throw new IndexOutOfBoundsException(MaidInventoryDiagnostics.MSG_MAID_SLOT + slot);
    }
}
