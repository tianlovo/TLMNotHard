package com.tianluoqaq.tlmnh.common.life.bed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.runtime.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.relauncher.Side;

/** 放置只在身份、来源和回调结果均可确认时提交；失败保留未知现场，不删除外来数据 */
public final class MaidBedPlacement {

    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();

    private MaidBedPlacement() {}

    public static boolean place(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side) {
        String key = MaidBedIds.ID_BED_PLACEMENT + player.getUniqueID();
        if (!world.isRemote && !IntegrationGuard.available(Side.SERVER, key)) {
            ProtectionEvents.warn(player);
            return false;
        }
        try {
            int direction = MathHelper.floor_double(
                player.rotationYaw * MaidBedLayout.BED_PLACEMENT_DIRECTION / MaidBedLayout.FULL_TURN_DEGREES_F
                    + MaidBedLayout.CENTER_OFFSET_D)
                & MaidBedLayout.LOW_TWO_BITS_MASK;
            int headX = x + BlockBed.field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE];
            int headZ = z + BlockBed.field_149981_a[direction][MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP];
            if (!empty(world, x, y, z) || !empty(world, headX, y, headZ)
                || !player.canPlayerEdit(x, y, z, side, stack)
                || !player.canPlayerEdit(headX, y, headZ, side, stack)
                || !World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
                || !World.doesBlockHaveSolidTopSurface(world, headX, y - 1, headZ)) return false;
            if (world.isRemote) return true;
            require(
                ACTIVE.get() == null && !world.restoringBlockSnapshots,
                MaidBedDiagnostics.MSG_NESTED_RESTORING_PLACEMENT_REFUSED);
            var attempt = new Attempt(world, player, stack, x, y, z, headX, headZ, direction);
            ACTIVE.set(attempt);
            try {
                var block = MaidLifeContent.maidBed;
                require(
                    world.setBlock(x, y, z, block, direction, MaidBedLayout.BLOCK_BED_SET_BLOCK_SIXTH_PARAM),
                    MaidBedDiagnostics.MSG_BED_FOOT_WRITE_FAILED);
                require(
                    attempt.footIntact() && empty(world, headX, y, headZ),
                    MaidBedDiagnostics.MSG_BED_AREA_CHANGED_AFTER_FOOT);
                require(
                    world.setBlock(
                        headX,
                        y,
                        headZ,
                        block,
                        direction | MaidBedLayout.BED_PLACEMENT_SET_BLOCK_FIFTH_PARAM,
                        MaidBedLayout.BLOCK_BED_SET_BLOCK_SIXTH_PARAM),
                    MaidBedDiagnostics.MSG_BED_HEAD_WRITE_FAILED);
                var bed = attempt.exactHead();
                require(bed.initializeItemColor(stack), MaidBedDiagnostics.MSG_BED_SOURCE_INITIALIZATION_REFUSED);
                // 回调期间床头仍未提交；异常、换Tile或移走来源都不能触发补物/破坏回滚
                world.notifyBlocksOfNeighborChange(x, y, z, block);
                world.notifyBlocksOfNeighborChange(headX, y, headZ, block);
                require(attempt.exactHead() == bed, MaidBedDiagnostics.MSG_BED_IDENTITY_CHANGED_DURING_CALLBACKS);
                require(
                    player.getCurrentEquippedItem() == stack && ItemStack.areItemStacksEqual(stack, attempt.source),
                    MaidBedDiagnostics.MSG_PLACEMENT_SOURCE_CHANGED_DURING_CALLBACKS);
                require(
                    IntegrationGuard.available(Side.SERVER, key),
                    MaidBedDiagnostics.MSG_PLACEMENT_STOPPED_BY_NESTED_FAILURE);
                // 从这里到返回不再调用世界/外部API；同线程完成标记与物品计数提交
                bed.finishPlacement(attempt.marker);
                if (!player.capabilities.isCreativeMode) stack.stackSize--;
                return true;
            } finally {
                ACTIVE.remove();
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, key, failure, player.getUniqueID());
            ProtectionEvents.warn(player);
            return false;
        }
    }

    /** 原生Chunk在设定world/坐标后validate，再放进Tile表；只登记本次确切床头坐标 */
    static void observe(TileEntityMaidBed bed) {
        var attempt = ACTIVE.get();
        if (attempt == null || bed.getWorldObj() != attempt.world
            || bed.xCoord != attempt.headX
            || bed.yCoord != attempt.y
            || bed.zCoord != attempt.headZ
            || attempt.created.contains(bed)) return;
        bed.stagePlacement(attempt.marker);
        attempt.created.add(bed);
    }

    static void reportRetained(TileEntityMaidBed bed, NBTBase raw) {
        UUID owner = null;
        if (raw instanceof NBTTagCompound tag && tag.hasKey(MaidBedNbtKeys.KEY_SCHEMA, Constants.NBT.TAG_INT)
            && tag.getInteger(MaidBedNbtKeys.KEY_SCHEMA) == MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP) {
            try {
                owner = UUID.fromString(tag.getString(MaidBedNbtKeys.KEY_OWNER));
            } catch (IllegalArgumentException ignored) {
                // 未知原值仍原样保留，不猜测主人或自动补物
            }
        }
        String key = owner != null ? MaidBedIds.ID_BED_PLACEMENT + owner
            : MaidBedIds.ID_BED_PLACEMENT_RETAINED + bed.getWorldObj().provider.dimensionId
                + MaidBedIds.PATH_SLASH_SEPARATOR
                + bed.xCoord
                + MaidBedIds.PATH_SLASH_SEPARATOR
                + bed.yCoord
                + MaidBedIds.PATH_SLASH_SEPARATOR
                + bed.zCoord;
        IntegrationGuard.report(
            Side.SERVER,
            key,
            new IllegalStateException(MaidBedDiagnostics.MSG_UNCOMMITTED_BED_SOURCE_RECORD_RETAINED),
            owner);
    }

    private static boolean empty(World world, int x, int y, int z) {
        return world.blockExists(x, y, z) && world.getBlock(x, y, z) == Blocks.air
            && world.getTileEntity(x, y, z) == null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Attempt {

        final World world;
        final ItemStack source;
        final NBTTagCompound marker = new NBTTagCompound();
        final List<TileEntityMaidBed> created = new ArrayList<>();
        final int x, y, z, headX, headZ, direction;

        Attempt(World world, EntityPlayer player, ItemStack stack, int x, int y, int z, int headX, int headZ,
            int direction) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.headX = headX;
            this.headZ = headZ;
            this.direction = direction;
            source = stack.copy();
            marker.setInteger(MaidBedNbtKeys.KEY_SCHEMA, MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP);
            marker.setString(
                MaidBedNbtKeys.KEY_TOKEN,
                UUID.randomUUID()
                    .toString());
            marker.setString(
                MaidBedNbtKeys.KEY_OWNER,
                player.getUniqueID()
                    .toString());
            marker.setTag(MaidBedNbtKeys.KEY_SOURCE, source.writeToNBT(new NBTTagCompound()));
        }

        boolean footIntact() {
            return world.getBlock(x, y, z) == MaidLifeContent.maidBed && world.getBlockMetadata(x, y, z) == direction
                && world.getTileEntity(x, y, z) == null;
        }

        TileEntityMaidBed exactHead() {
            require(
                created.size() == MaidBedLayout.BLOCK_BED_DEFAULT_UNIT_STEP && footIntact(),
                MaidBedDiagnostics.MSG_AMBIGUOUS_HEAD_IDENTITY_OR_CHANGED);
            var bed = created.get(MaidBedLayout.BLOCK_BED_DEFAULT_ZERO_BASE);
            require(
                world.getBlock(headX, y, headZ) == MaidLifeContent.maidBed
                    && world.getBlockMetadata(headX, y, headZ)
                        == (direction | MaidBedLayout.BED_PLACEMENT_SET_BLOCK_FIFTH_PARAM)
                    && world.getTileEntity(headX, y, headZ) == bed
                    && !bed.isInvalid()
                    && bed.placementPending()
                    && !bed.dropQueued(),
                MaidBedDiagnostics.MSG_CHANGED_BED_HEAD_RETAINED);
            return bed;
        }
    }
}
