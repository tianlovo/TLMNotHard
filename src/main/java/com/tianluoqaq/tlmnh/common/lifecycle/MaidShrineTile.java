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
    private int clientStorageKind;

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public net.minecraft.util.AxisAlignedBB getRenderBoundingBox() {
        // 原模型visible_bounds宽3/高2.5/中心Y.75，屋顶高于单方块碰撞，不能随碰撞盒被裁去
        return net.minecraft.util.AxisAlignedBB
            .getBoundingBox(xCoord - 1, yCoord - .5, zCoord - 1, xCoord + 2, yCoord + 2, zCoord + 2);
    }

    public ItemStack getFilm() {
        return film == null ? null : film.copy();
    }

    /** 仅供渲染：不向客户端暴露胶片token、主人或原始女仆NBT */
    public ItemStack displayItem() {
        int kind = worldObj != null && worldObj.isRemote ? clientStorageKind : storageKind();
        return switch (kind) {
            case 1 -> new ItemStack(com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent.FILM);
            case 2 -> new ItemStack(com.tianluoqaq.tlmnh.common.protection.RecoveryParcel.ITEM);
            default -> null;
        };
    }

    private int storageKind() {
        return film == null ? 0 : MaidLifecycleItems.isFilm(film) ? 1 : 2;
    }

    @Override
    public boolean shouldRefresh(net.minecraft.block.Block oldBlock, net.minecraft.block.Block newBlock, int oldMeta,
        int newMeta, net.minecraft.world.World world, int x, int y, int z) {
        return oldBlock != newBlock;
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

    boolean beginRedemptionUse(ItemStack expected) {
        if (busy || !ItemStack.areItemStacksEqual(film, expected)) return false;
        busy = true;
        return true;
    }

    void consumeUse(ItemStack expected) {
        if (!busy || !ItemStack.areItemStacksEqual(film, expected))
            throw new IllegalStateException("Shrine film no longer belongs to this redemption");
        film = null;
        changed();
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
        tag.setByte("DisplayVersion", (byte) 1);
        tag.setByte("StorageKind", (byte) storageKind());
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager network, S35PacketUpdateTileEntity packet) {
        // S35只更新客户端显示字段，绝不能作为服务端持久StorageItem输入
        if (worldObj == null || !worldObj.isRemote) return;
        var tag = packet.func_148857_g();
        clientStorageKind = tag != null && tag.hasKey("DisplayVersion", 1)
            && tag.getByte("DisplayVersion") == 1
            && tag.hasKey("StorageKind", 1)
            && tag.getByte("StorageKind") >= 0
            && tag.getByte("StorageKind") <= 2 ? tag.getByte("StorageKind") : 0;
    }
}
