package com.tianluoqaq.tlmnh.common.life.cabinet;

import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.lifecycle.MaidTileReturns;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.recovery.RecoveryStore;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 上游最小 27 格零食柜，使用原生箱子界面，不增加厨房联动 */
public final class TileEntitySnackCabinet extends TileEntity implements IInventory {

    private boolean displayChecked;
    private NBTBase customName;
    private final CabinetOpeners openers = new CabinetOpeners(this);

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote
            || isInvalid()
            || worldObj.getTileEntity(xCoord, yCoord, zCoord) != this) return;
        if (!displayChecked) {
            displayChecked = true;
            // 旧占位柜加载时已有原版蛋糕，不等待一次额外的邻居事件才建立原玻璃罩
            MaidLifeContent.snackCabinet.refreshDisplay(worldObj, xCoord, yCoord, zCoord);
        }
        openers.tick();
    }

    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
        // Forge默认非原生Tile在metadata变化时会被重建；方向/玻璃罩不能清空柜内物品
        return oldBlock != newBlock;
    }

    private final RecoveryStore recovery = new RecoveryStore(this);

    public RecoveryStore recovery() {
        return recovery;
    }

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
        if (MaidTileReturns.isPrepared(this)) return null;
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
        if (MaidTileReturns.isPrepared(this)) return null;
        var result = items[slot];
        items[slot] = null;
        markDirty();
        return result;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (MaidTileReturns.isPrepared(this))
            throw new IllegalStateException("Cabinet material is already held for its original removal");
        stack = ProtectedItemCodec.admit(stack, getInventoryStackLimit());
        items[slot] = stack;
        markDirty();
    }

    void clearForReturn() {
        if (!MaidTileReturns.isPrepared(this)) throw new IllegalStateException("Original cabinet escrow required");
        Arrays.fill(items, null);
        recovery.clear();
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return hasCustomInventoryName() ? ((NBTTagString) customName).func_150285_a_()
            : "container.tlmnh.snack_cabinet";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return customName instanceof NBTTagString name && !name.func_150285_a_()
            .isEmpty();
    }

    public void setCustomInventoryName(String name) {
        customName = new NBTTagString(name);
        markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return worldObj != null && player != null
            && !MaidTileReturns.isPrepared(this)
            && player.isEntityAlive()
            && player.worldObj == worldObj
            && worldObj.blockExists(xCoord, yCoord, zCoord)
            && worldObj.getTileEntity(xCoord, yCoord, zCoord) == this
            && player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64;
    }

    @Override
    public void openInventory() {
        openers.requestCheck();
    }

    @Override
    public void closeInventory() {
        openers.requestCheck();
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return stack != null;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        var contents = new NBTTagList();
        for (int slot = 0; slot < items.length; slot++) if (items[slot] != null) {
            var item = ProtectedItemCodec.encode(items[slot]);
            item.setByte("Slot", (byte) slot);
            contents.appendTag(item);
        }
        tag.setTag("Items", contents);
        tag.setTag(RecoveryStore.TAG, recovery.write());
        if (customName != null) tag.setTag("CustomName", customName.copy());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        displayChecked = false;
        openers.reset();
        customName = tag.hasKey("CustomName") ? tag.getTag("CustomName")
            .copy() : null;
        Arrays.fill(items, null);
        recovery.read(tag.getTagList(RecoveryStore.TAG, Constants.NBT.TAG_COMPOUND));
        recovery.retainInvalidList(tag, "Items");
        recovery.retainInvalidList(tag, RecoveryStore.TAG);
        var contents = tag.getTagList("Items", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < contents.tagCount(); index++) {
            var item = contents.getCompoundTagAt(index);
            int slot = item.getByte("Slot") & 255;
            if (!item.hasKey("Slot", Constants.NBT.TAG_BYTE) || slot >= items.length || items[slot] != null)
                recovery.retain(item);
            else {
                var stack = ProtectedItemCodec.decode(item);
                items[slot] = stack == null ? ProtectedItemCodec.seal(item, "empty_entry") : stack;
            }
        }
    }
}
