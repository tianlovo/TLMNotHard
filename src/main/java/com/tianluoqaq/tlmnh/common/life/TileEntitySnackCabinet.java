package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

/** 上游最小 27 格零食柜，使用原生箱子界面，不增加厨房联动 */
public final class TileEntitySnackCabinet extends TileEntity implements IInventory {

    private final ItemStack[] items = new ItemStack[27];

    @Override
    public int getSizeInventory() {
        return items.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return items[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        if (count <= 0 || items[slot] == null) return null;
        ItemStack removed;
        if (items[slot].stackSize <= count) {
            removed = items[slot];
            items[slot] = null;
        } else removed = items[slot].splitStack(count);
        markDirty();
        return removed;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        var result = items[slot];
        items[slot] = null;
        markDirty();
        return result;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (stack != null) {
            if (stack.stackSize <= 0) stack = null;
            else stack.stackSize = Math.min(stack.stackSize, Math.min(64, stack.getMaxStackSize()));
        }
        items[slot] = stack;
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "container.tlmnh.snack_cabinet";
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
    public boolean isUseableByPlayer(EntityPlayer player) {
        return worldObj != null && player != null
            && player.isEntityAlive()
            && player.worldObj == worldObj
            && worldObj.blockExists(xCoord, yCoord, zCoord)
            && worldObj.getTileEntity(xCoord, yCoord, zCoord) == this
            && player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return stack != null;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        var contents = new NBTTagList();
        for (int slot = 0; slot < items.length; slot++) if (items[slot] != null) {
            var item = new NBTTagCompound();
            item.setByte("Slot", (byte) slot);
            items[slot].writeToNBT(item);
            contents.appendTag(item);
        }
        tag.setTag("Items", contents);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        java.util.Arrays.fill(items, null);
        var contents = tag.getTagList("Items", 10);
        for (int index = 0; index < contents.tagCount(); index++) {
            var item = contents.getCompoundTagAt(index);
            int slot = item.getByte("Slot") & 255;
            if (slot < items.length) items[slot] = ItemStack.loadItemStackFromNBT(item);
        }
    }
}
