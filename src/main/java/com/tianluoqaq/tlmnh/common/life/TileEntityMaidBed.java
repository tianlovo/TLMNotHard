package com.tianluoqaq.tlmnh.common.life;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;

/** 只有床头保存临时占用；睡眠动作每 tick 续租 */
public final class TileEntityMaidBed extends TileEntity {

    private static final String COLOR_TAG = "BedColor";
    private final MaidBedLease lease = new MaidBedLease();
    private EntityMaid occupant;
    private NBTBase savedColor;

    /** 无颜色的旧移植存档沿新床默认粉色；未知类型/编号返回null并原样保存 */
    public MaidBedColor getColor() {
        if (savedColor == null) return MaidBedColor.PINK;
        return savedColor instanceof NBTTagInt value ? MaidBedColor.byId(value.func_150287_d()) : null;
    }

    public boolean setColor(MaidBedColor color) {
        if (color == null || worldObj == null
            || worldObj.isRemote
            || getColor() == null
            || getColor() == color
            || !complete()
            || worldObj.getTileEntity(xCoord, yCoord, zCoord) != this) return false;
        savedColor = new NBTTagInt(color.id);
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        return true;
    }

    private void writeColor(NBTTagCompound tag) {
        tag.setTag(COLOR_TAG, savedColor == null ? new NBTTagInt(MaidBedColor.PINK.id) : savedColor.copy());
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeColor(tag);
    }

    @Override
    public Packet getDescriptionPacket() {
        var tag = new NBTTagCompound();
        writeColor(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager network, S35PacketUpdateTileEntity packet) {
        if (worldObj == null || !worldObj.isRemote
            || packet.func_148856_c() != xCoord
            || packet.func_148855_d() != yCoord
            || packet.func_148854_e() != zCoord) return;
        var tag = packet.func_148857_g();
        savedColor = tag.hasKey(COLOR_TAG) ? tag.getTag(COLOR_TAG)
            .copy() : null;
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public boolean complete() {
        if (worldObj == null || isInvalid()
            || !worldObj.blockExists(xCoord, yCoord, zCoord)
            || !(worldObj.getBlock(xCoord, yCoord, zCoord) instanceof BlockMaidBed)) return false;
        int meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
        if (!BlockBed.isBlockHeadOfBed(meta)) return false;
        int direction = meta & 3;
        int footX = xCoord - BlockBed.field_149981_a[direction][0];
        int footZ = zCoord - BlockBed.field_149981_a[direction][1];
        return worldObj.blockExists(footX, yCoord, footZ)
            && worldObj.getBlock(footX, yCoord, footZ) == worldObj.getBlock(xCoord, yCoord, zCoord)
            && (worldObj.getBlockMetadata(footX, yCoord, footZ) & 11) == direction;
    }

    public boolean claim(EntityMaid maid) {
        if (worldObj == null || worldObj.isRemote
            || maid == null
            || maid.worldObj != worldObj
            || !maid.isEntityAlive()
            || !complete()) return false;
        if (!lease.claim(maid.getUniqueID(), worldObj.getTotalWorldTime())) return false;
        occupant = maid;
        occupied(true);
        return true;
    }

    public boolean available(EntityMaid maid) {
        return maid != null && worldObj != null
            && complete()
            && lease.available(maid.getUniqueID(), worldObj.getTotalWorldTime());
    }

    public boolean isOwnedBy(EntityMaid maid) {
        return maid != null && worldObj != null && lease.isOwnedBy(maid.getUniqueID(), worldObj.getTotalWorldTime());
    }

    public void release(EntityMaid maid) {
        if (maid != null && lease.release(maid.getUniqueID())) {
            occupant = null;
            occupied(false);
        }
    }

    private void occupied(boolean occupied) {
        if (worldObj == null || worldObj.isRemote
            || !worldObj.blockExists(xCoord, yCoord, zCoord)
            || !(worldObj.getBlock(xCoord, yCoord, zCoord) instanceof BlockMaidBed)) return;
        int old = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
        int next = occupied ? old | 4 : old & ~4;
        if (old != next) worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, next, 2);
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;
        if (occupant == null || !occupant.isEntityAlive()
            || occupant.worldObj != worldObj
            || worldObj.getEntityByID(occupant.getEntityId()) != occupant
            || lease.expired(worldObj.getTotalWorldTime())
            || !complete()) clear();
    }

    private void clear() {
        if (occupant != null) occupant.setPose(MaidPose.SLEEPING, false);
        occupant = null;
        lease.clear();
        occupied(false);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        savedColor = tag.hasKey(COLOR_TAG) ? tag.getTag(COLOR_TAG)
            .copy() : null;
        lease.clear();
        occupant = null;
    }

    @Override
    public void invalidate() {
        if (worldObj != null && !worldObj.isRemote) clear();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        // 不查询区块或改 metadata；重载后的第一次 tick 会清掉陈旧占用位
        if (occupant != null && worldObj != null && !worldObj.isRemote) occupant.setPose(MaidPose.SLEEPING, false);
        occupant = null;
        lease.clear();
        super.onChunkUnload();
    }

    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
        return oldBlock != newBlock || (oldMeta & 8) != (newMeta & 8);
    }
}
