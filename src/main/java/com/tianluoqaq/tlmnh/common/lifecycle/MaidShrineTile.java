package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.protection.RecoveryParcel;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

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
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        // 原模型visible_bounds宽3/高2.5/中心Y.75，屋顶高于单方块碰撞，不能随碰撞盒被裁去
        return AxisAlignedBB.getBoundingBox(xCoord - 1, yCoord - .5, zCoord - 1, xCoord + 2, yCoord + 2, zCoord + 2);
    }

    public ItemStack getFilm() {
        return film == null ? null : film.copy();
    }

    /** 仅供渲染：不向客户端暴露胶片token、主人或原始女仆NBT */
    public ItemStack displayItem() {
        int kind = worldObj != null && worldObj.isRemote ? clientStorageKind : storageKind();
        return switch (kind) {
            case 1 -> new ItemStack(MaidResurrectionContent.FILM);
            case 2 -> new ItemStack(RecoveryParcel.ITEM);
            default -> null;
        };
    }

    private int storageKind() {
        return film == null ? 0 : MaidLifecycleItems.isFilm(film) ? 1 : 2;
    }

    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
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
        var snapshot = film.copy();
        busy = true;
        return snapshot;
    }

    boolean beginInsertUse() {
        if (busy || film != null) return false;
        busy = true;
        return true;
    }

    void replaceUse(ItemStack expected, ItemStack replacement) {
        if (!busy || !ItemStack.areItemStacksEqual(film, expected))
            throw new IllegalStateException("Unknown shrine slot successor retained");
        film = ItemStack.copyItemStack(replacement);
        changed();
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
        var stored = ProtectedItemCodec.decodeField(tag, "StorageItem");
        film = stored == null || MaidLifecycleItems.isFilm(stored) || stored.getItem() == RecoveryParcel.ITEM ? stored
            : ProtectedItemCodec.seal(tag.getCompoundTag("StorageItem"), "shrine_slot");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (film != null) tag.setTag("StorageItem", ProtectedItemCodec.encode(film));
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
        clientStorageKind = tag != null && tag.hasKey("DisplayVersion", Constants.NBT.TAG_BYTE)
            && tag.getByte("DisplayVersion") == 1
            && tag.hasKey("StorageKind", Constants.NBT.TAG_BYTE)
            && tag.getByte("StorageKind") >= 0
            && tag.getByte("StorageKind") <= 2 ? tag.getByte("StorageKind") : 0;
    }
}
