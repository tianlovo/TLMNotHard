package com.tianluoqaq.tlmnh.common.life;

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
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.relauncher.Side;

/** 放置只在身份、来源和回调结果均可确认时提交；失败保留未知现场，不删除外来数据 */
public final class MaidBedPlacement {

    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();

    private MaidBedPlacement() {}

    public static boolean place(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side) {
        String key = "bed_placement/" + player.getUniqueID();
        if (!world.isRemote && !IntegrationGuard.available(Side.SERVER, key)) {
            ProtectionEvents.warn(player);
            return false;
        }
        try {
            int direction = MathHelper.floor_double(player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
            int headX = x + BlockBed.field_149981_a[direction][0];
            int headZ = z + BlockBed.field_149981_a[direction][1];
            if (!empty(world, x, y, z) || !empty(world, headX, y, headZ)
                || !player.canPlayerEdit(x, y, z, side, stack)
                || !player.canPlayerEdit(headX, y, headZ, side, stack)
                || !World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
                || !World.doesBlockHaveSolidTopSurface(world, headX, y - 1, headZ)) return false;
            if (world.isRemote) return true;
            require(ACTIVE.get() == null && !world.restoringBlockSnapshots, "Nested/restoring placement refused");
            var attempt = new Attempt(world, player, stack, x, y, z, headX, headZ, direction);
            ACTIVE.set(attempt);
            try {
                var block = MaidLifeContent.maidBed;
                require(world.setBlock(x, y, z, block, direction, 2), "Bed foot write failed");
                require(attempt.footIntact() && empty(world, headX, y, headZ), "Bed area changed after foot write");
                require(world.setBlock(headX, y, headZ, block, direction | 8, 2), "Bed head write failed");
                var bed = attempt.exactHead();
                require(bed.initializeItemColor(stack), "Bed source initialization refused");
                // 回调期间床头仍未提交；异常、换Tile或移走来源都不能触发补物/破坏回滚
                world.notifyBlocksOfNeighborChange(x, y, z, block);
                world.notifyBlocksOfNeighborChange(headX, y, headZ, block);
                require(attempt.exactHead() == bed, "Bed identity changed during callbacks");
                require(
                    player.getCurrentEquippedItem() == stack && ItemStack.areItemStacksEqual(stack, attempt.source),
                    "Placement source changed during callbacks");
                require(IntegrationGuard.available(Side.SERVER, key), "Placement stopped by a nested failure");
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
        if (raw instanceof NBTTagCompound tag && tag.hasKey("Schema", Constants.NBT.TAG_INT)
            && tag.getInteger("Schema") == 1) {
            try {
                owner = UUID.fromString(tag.getString("Owner"));
            } catch (IllegalArgumentException ignored) {
                // 未知原值仍原样保留，不猜测主人或自动补物
            }
        }
        String key = owner != null ? "bed_placement/" + owner
            : "bed_placement_retained/" + bed.getWorldObj().provider.dimensionId
                + "/"
                + bed.xCoord
                + "/"
                + bed.yCoord
                + "/"
                + bed.zCoord;
        IntegrationGuard.report(
            Side.SERVER,
            key,
            new IllegalStateException("Uncommitted bed source record retained; no automatic cleanup or reissue"),
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
            marker.setInteger("Schema", 1);
            marker.setString(
                "Token",
                UUID.randomUUID()
                    .toString());
            marker.setString(
                "Owner",
                player.getUniqueID()
                    .toString());
            marker.setTag("Source", source.writeToNBT(new NBTTagCompound()));
        }

        boolean footIntact() {
            return world.getBlock(x, y, z) == MaidLifeContent.maidBed && world.getBlockMetadata(x, y, z) == direction
                && world.getTileEntity(x, y, z) == null;
        }

        TileEntityMaidBed exactHead() {
            require(created.size() == 1 && footIntact(), "Ambiguous head identity or changed foot retained");
            var bed = created.get(0);
            require(
                world.getBlock(headX, y, headZ) == MaidLifeContent.maidBed
                    && world.getBlockMetadata(headX, y, headZ) == (direction | 8)
                    && world.getTileEntity(headX, y, headZ) == bed
                    && !bed.isInvalid()
                    && bed.placementPending()
                    && !bed.dropQueued(),
                "Changed bed head retained");
            return bed;
        }
    }
}
