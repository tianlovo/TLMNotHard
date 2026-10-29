package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 六格背包、双手与护甲的统一槽位视图，装备仍只有实体中的一份存储 */
public final class MaidInventory implements IInventory {

    public static final int BACKPACK_SIZE = 6;
    public static final int MAIN_HAND = 6;
    public static final int OFF_HAND = 7;
    public static final int ARMOR_START = 8;
    public static final int TOTAL_SIZE = 12;

    private final EntityMaid maid;
    private final ItemStack[] backpack = new ItemStack[BACKPACK_SIZE];

    public MaidInventory(EntityMaid maid) {
        this.maid = maid;
    }

    @Override
    public int getSizeInventory() {
        return TOTAL_SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        checkSlot(slot);
        if (slot < BACKPACK_SIZE) return backpack[slot];
        if (slot == MAIN_HAND) return maid.getHeldItem();
        if (slot == OFF_HAND) return maid.getOffhandItem();
        return maid.getEquipmentInSlot(slot - ARMOR_START + 1);
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
        if (stack != null && (stack.stackSize <= 0 || stack.getItem() == null)) stack = null;
        if (stack != null)
            stack.stackSize = Math.min(stack.stackSize, Math.min(getInventoryStackLimit(), stack.getMaxStackSize()));
        if (slot < BACKPACK_SIZE) backpack[slot] = stack;
        else if (slot == MAIN_HAND) maid.setCurrentItemOrArmor(0, stack);
        else if (slot == OFF_HAND) maid.setOffhandItem(stack);
        else maid.setCurrentItemOrArmor(slot - ARMOR_START + 1, stack);
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "container.tlmnh.maid";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        // DataWatcher 比较副本，捕获使用道具时对副手 ItemStack 的原地修改
        maid.syncOffhand();
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return maid.isEntityAlive() && player != null
            && player.isEntityAlive()
            && maid.isOwner(player)
            && player.worldObj == maid.worldObj
            && player.getDistanceSqToEntity(maid) <= 64.0D;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        checkSlot(slot);
        if (stack == null) return false;
        if (slot < ARMOR_START) return true;
        return stack.getItem()
            .isValidArmor(stack, 3 - (slot - ARMOR_START), maid);
    }

    public NBTTagList writeBackpack() {
        var items = new NBTTagList();
        for (int slot = 0; slot < BACKPACK_SIZE; slot++) {
            if (backpack[slot] != null) {
                var item = new NBTTagCompound();
                item.setByte("Slot", (byte) slot);
                backpack[slot].writeToNBT(item);
                items.appendTag(item);
            }
        }
        return items;
    }

    public void readBackpack(NBTTagList items) {
        for (int slot = 0; slot < BACKPACK_SIZE; slot++) backpack[slot] = null;
        for (int index = 0; index < items.tagCount(); index++) {
            var item = items.getCompoundTagAt(index);
            int slot = item.getByte("Slot") & 255;
            if (slot < BACKPACK_SIZE) setInventorySlotContents(slot, ItemStack.loadItemStackFromNBT(item));
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= TOTAL_SIZE) throw new IndexOutOfBoundsException("Maid slot: " + slot);
    }
}
