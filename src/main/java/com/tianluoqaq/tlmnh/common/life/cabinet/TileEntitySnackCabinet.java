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
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectionRecoveryNbtKeys;
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

    private final ItemStack[] items = new ItemStack[SnackCabinetLayout.TILE_ENTITY_DOMAIN_PARAM];

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
            throw new IllegalStateException(SnackCabinetDiagnostics.MSG_CABINET_MATERIAL_IS_ALREADY_HELD);
        stack = ProtectedItemCodec.admit(stack, getInventoryStackLimit());
        items[slot] = stack;
        markDirty();
    }

    void clearForReturn() {
        if (!MaidTileReturns.isPrepared(this))
            throw new IllegalStateException(SnackCabinetDiagnostics.MSG_ORIGINAL_CABINET_ESCROW_REQUIRED);
        Arrays.fill(items, null);
        recovery.clear();
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return hasCustomInventoryName() ? ((NBTTagString) customName).func_150285_a_()
            : SnackCabinetIds.I18N_CONTAINER_TLMNH_SNACK_CABINET;
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
        return SnackCabinetLayout.TILE_ENTITY_GET_INVENTORY_STACK_LIMIT_PARAM;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return worldObj != null && player != null
            && !MaidTileReturns.isPrepared(this)
            && player.isEntityAlive()
            && player.worldObj == worldObj
            && worldObj.blockExists(xCoord, yCoord, zCoord)
            && worldObj.getTileEntity(xCoord, yCoord, zCoord) == this
            && player.getDistanceSq(
                xCoord + SnackCabinetLayout.CENTER_OFFSET_D,
                yCoord + SnackCabinetLayout.CENTER_OFFSET_D,
                zCoord + SnackCabinetLayout.CENTER_OFFSET_D)
                <= SnackCabinetLayout.TILE_ENTITY_GET_INVENTORY_STACK_LIMIT_PARAM;
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
            item.setByte(SnackCabinetNbtKeys.KEY_SLOT, (byte) slot);
            contents.appendTag(item);
        }
        tag.setTag(SnackCabinetNbtKeys.KEY_ITEMS, contents);
        tag.setTag(ProtectionRecoveryNbtKeys.TAG, recovery.write());
        if (customName != null) tag.setTag(SnackCabinetNbtKeys.KEY_CUSTOM_NAME, customName.copy());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        displayChecked = false;
        openers.reset();
        customName = tag.hasKey(SnackCabinetNbtKeys.KEY_CUSTOM_NAME) ? tag.getTag(SnackCabinetNbtKeys.KEY_CUSTOM_NAME)
            .copy() : null;
        Arrays.fill(items, null);
        recovery.read(tag.getTagList(ProtectionRecoveryNbtKeys.TAG, Constants.NBT.TAG_COMPOUND));
        recovery.retainInvalidList(tag, SnackCabinetNbtKeys.KEY_ITEMS);
        recovery.retainInvalidList(tag, ProtectionRecoveryNbtKeys.TAG);
        var contents = tag.getTagList(SnackCabinetNbtKeys.KEY_ITEMS, Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < contents.tagCount(); index++) {
            var item = contents.getCompoundTagAt(index);
            int slot = item.getByte(SnackCabinetNbtKeys.KEY_SLOT) & SnackCabinetLayout.UNSIGNED_BYTE_MASK;
            if (!item.hasKey(SnackCabinetNbtKeys.KEY_SLOT, Constants.NBT.TAG_BYTE) || slot >= items.length
                || items[slot] != null) recovery.retain(item);
            else {
                var stack = ProtectedItemCodec.decode(item);
                items[slot] = stack == null ? ProtectedItemCodec.seal(item, SnackCabinetIds.ID_EMPTY_ENTRY) : stack;
            }
        }
    }
}
