package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/** 神龛的唯一胶片槽，保存与网络同步均使用副本 */
public final class MaidShrineTile extends TileEntity {

    private ItemStack film;
    private boolean busy;

    @Override
    public boolean canUpdate() {
        return false;
    }

    public ItemStack getFilm() {
        return film == null ? null : film.copy();
    }

    public boolean insert(ItemStack stack) {
        if (busy || film != null || !MaidLifecycleItems.isFilm(stack)) return false;
        film = stack.copy();
        changed();
        return true;
    }

    public ItemStack extract() {
        if (busy) return null;
        return extractForBreak();
    }

    ItemStack extractForBreak() {
        var result = film;
        film = null;
        changed();
        return result;
    }

    ItemStack beginUse() {
        if (busy || film == null) return null;
        busy = true;
        return film.copy();
    }

    void finishUse(boolean consumed) {
        if (!busy) throw new IllegalStateException("No shrine transaction");
        if (consumed) film = null;
        busy = false;
        if (consumed) changed();
    }

    private void changed() {
        markDirty();
        if (worldObj != null) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        var stored = com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.decodeField(tag, "StorageItem");
        film = stored == null || MaidLifecycleItems.isFilm(stored)
            || stored.getItem() == com.tianluoqaq.tlmnh.common.protection.RecoveryParcel.ITEM ? stored
                : com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec
                    .seal(tag.getCompoundTag("StorageItem"), "shrine_slot");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (film != null)
            tag.setTag("StorageItem", com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.encode(film));
        else tag.removeTag("StorageItem");
    }

    @Override
    public Packet getDescriptionPacket() {
        var tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager network, S35PacketUpdateTileEntity packet) {
        readFromNBT(packet.func_148857_g());
    }
}
