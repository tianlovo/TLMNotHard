package com.tianluoqaq.tlmnh.compat.malisisdoors.common.combat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.malisisdoors.MalisisDoorsCompat;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** 精确动画栅栏与默认旋转木门的身体箱，不调用World.getTileEntity或未知movement */
public final class MalisisCombatGeometry {

    public enum Result {
        SAFE,
        UNKNOWN,
        BUDGET
    }

    public static final class Sample {

        public final Result result;
        public final AxisAlignedBB body;
        public final DoorCell doorCell;

        private Sample(Result result, AxisAlignedBB body) {
            this(result, body, null);
        }

        private Sample(Result result, AxisAlignedBB body, DoorCell doorCell) {
            this.result = result;
            this.body = body;
            this.doorCell = doorCell;
        }
    }

    private static final String FEATURE = "combat/geometry/malisis_fence_gate";
    private static final String WOOD_FEATURE = "combat/geometry/malisis_wood_door";
    private static final int WOOD_METADATA_QUERIES = 4;
    private static final Api API = api();
    private static final Wood WOOD = wood();

    private MalisisCombatGeometry() {}

    public static boolean matches(Block block) {
        return API != null && (block == Blocks.fence_gate && block.getClass() == API.gate
            && IntegrationGuard.available(Side.SERVER, FEATURE)
            || WOOD != null && block == Blocks.wooden_door
                && block.getClass() == WOOD.door
                && IntegrationGuard.available(Side.SERVER, WOOD_FEATURE));
    }

    public static Sample capture(World world, Block block, int x, int y, int z, CombatWorkQuota quota) {
        if (world == null || world.isRemote
            || quota == null
            || !matches(block)
            || quota.tick != world.getTotalWorldTime()) return sample(Result.UNKNOWN);
        if (block == Blocks.wooden_door) return captureWood(world, block, x, y, z, quota);
        // 已加载分区、原map对象和外层metadata分别收费，不枚举整张表
        if (!quota.spend(Work.CHUNK)) return sample(Result.BUDGET);
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)) return sample(Result.UNKNOWN);
        var chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        TileEntity tile = chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        if (tile == null || tile.getClass() != API.tile
            || tile.getWorldObj() != world
            || tile.xCoord != x
            || tile.yCoord != y
            || tile.zCoord != z
            || tile.isInvalid()) return sample(Result.UNKNOWN);
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        int metadata = world.getBlockMetadata(x, y, z);
        if (!quota.spend(Work.BLOCK, 3)) return sample(Result.BUDGET);
        try {
            // isMoving是字段读；isOpened可刷新原生缓存，已预付最多3个真实查询
            boolean moving = (Boolean) API.moving.invoke(tile);
            boolean opened = moving || (Boolean) API.opened.invoke(tile);
            if (opened || (metadata & 4) != 0) return new Sample(Result.SAFE, null);
            AxisAlignedBB body = metadata == 0 || metadata == 2
                ? AxisAlignedBB.getBoundingBox(x, y, (float) z + .375F, x + 1, (float) y + 1.5F, (float) z + .625F)
                : AxisAlignedBB.getBoundingBox((float) x + .375F, y, z, (float) x + .625F, (float) y + 1.5F, z + 1);
            return new Sample(Result.SAFE, body);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, FEATURE, failure);
            return sample(Result.UNKNOWN);
        }
    }

    private static Sample captureWood(World world, Block block, int x, int y, int z, CombatWorkQuota quota) {
        if (!quota.spend(Work.CHUNK)) return sample(Result.BUDGET);
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)) return sample(Result.UNKNOWN);
        var chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        var tiles = chunk.chunkTileEntityMap;
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        int queryMetadata = world.getBlockMetadata(x, y, z);
        int bottomY = (queryMetadata & 8) == 0 ? y : y - 1;
        if (bottomY < 0 || bottomY >= 255) return sample(Result.UNKNOWN);
        if (!quota.spend(Work.BLOCK, 4)) return sample(Result.BUDGET);
        Block lower = world.getBlock(x, bottomY, z), upper = world.getBlock(x, bottomY + 1, z);
        int lowerMetadata = world.getBlockMetadata(x, bottomY, z),
            upperMetadata = world.getBlockMetadata(x, bottomY + 1, z);
        if (lower != block || upper != block
            || (lowerMetadata & ~7) != 0
            || (upperMetadata & ~9) != 0
            || (upperMetadata & 8) == 0
            || queryMetadata != (y == bottomY ? lowerMetadata : upperMetadata)) return sample(Result.UNKNOWN);
        var bottomKey = new ChunkPosition(x & 15, bottomY, z & 15);
        var topKey = new ChunkPosition(x & 15, bottomY + 1, z & 15);
        if (!quota.spend(Work.BLOCK, 2)) return sample(Result.BUDGET);
        TileEntity tile = tiles.get(bottomKey);
        if (!woodTile(tile, world, x, bottomY, z) || tiles.get(topKey) != null) return sample(Result.UNKNOWN);
        try {
            if (!quota.spend(Work.COLLISION, 8)) return sample(Result.BUDGET);
            Object blockDescriptor = WOOD.blockDescriptor.invoke(block), descriptor = WOOD.rawDescriptor.get(tile);
            if (blockDescriptor == null || blockDescriptor.getClass() != WOOD.vanilla
                || descriptor == null
                || descriptor.getClass() != WOOD.descriptor && descriptor.getClass() != WOOD.vanilla)
                return sample(Result.UNKNOWN);
            if (WOOD.descriptorBlock.invoke(blockDescriptor) != block
                || WOOD.descriptorBlock.invoke(descriptor) != block
                || WOOD.descriptorTile.invoke(blockDescriptor) != WOOD.tile
                || WOOD.descriptorTile.invoke(descriptor) != WOOD.tile) return sample(Result.UNKNOWN);
            Object movement = WOOD.descriptorMovement.invoke(descriptor);
            if (movement == null || movement.getClass() != WOOD.rotating
                || movement != WOOD.descriptorMovement.invoke(blockDescriptor)) return sample(Result.UNKNOWN);
            // 类型已经逐项锁定，才调用会查看descriptor的原生getter
            if (!quota.spend(Work.COLLISION, 5)) return sample(Result.BUDGET);
            if (WOOD.tileDescriptor.invoke(tile) != descriptor || (Boolean) API.moving.invoke(tile))
                return sample(Result.UNKNOWN);
            Object state = WOOD.state.invoke(tile);
            boolean centered = (Boolean) WOOD.centered.invoke(tile);
            if (!(state instanceof Enum<?>value) || !value.name()
                .equals("CLOSED")
                && !value.name()
                    .equals("OPENED"))
                return sample(Result.UNKNOWN);
            if (!quota.spend(Work.BLOCK, WOOD_METADATA_QUERIES)) return sample(Result.BUDGET);
            int metadata = tile.getBlockMetadata();
            int expected = lowerMetadata & 7 | ((upperMetadata & 1) != 0 ? 16 : 0);
            boolean opened = value.name()
                .equals("OPENED");
            if (metadata != expected || ((metadata & 4) != 0) != opened) return sample(Result.UNKNOWN);
            // 原生Rotating入口内部最多两次getBlockMetadata；预付其所有嵌套查询
            if (!quota.spend(Work.BLOCK, WOOD_METADATA_QUERIES * 2) || !quota.spend(Work.SHAPE, 3))
                return sample(Result.BUDGET);
            Object produced = WOOD.bounds.invoke(movement, tile, y != bottomY, WOOD.collisionType);
            if (!(produced instanceof AxisAlignedBB body) || body.getClass() != AxisAlignedBB.class)
                return sample(Result.UNKNOWN);
            if (centered) body.offset(0, 0, .40625);
            Object direction = WOOD.direction.invoke(null, metadata & 3);
            if (WOOD.rotate.invoke(null, body, direction) != body) return sample(Result.UNKNOWN);
            body.offset(x, y, z);
            // centered opened可能越cell，现单格证明不能把该邻格默认为净空
            if (!withinCell(body, x, y, z)) return sample(Result.UNKNOWN);
            if (!quota.spend(Work.BLOCK, WOOD_METADATA_QUERIES + 4 + 2) || !quota.spend(Work.COLLISION, 10))
                return sample(Result.BUDGET);
            if (tile.getBlockMetadata() != metadata || world.getBlock(x, bottomY, z) != block
                || world.getBlock(x, bottomY + 1, z) != block
                || world.getBlockMetadata(x, bottomY, z) != lowerMetadata
                || world.getBlockMetadata(x, bottomY + 1, z) != upperMetadata
                || chunk.chunkTileEntityMap != tiles
                || tiles.get(bottomKey) != tile
                || tiles.get(topKey) != null
                || !woodTile(tile, world, x, bottomY, z)
                || WOOD.rawDescriptor.get(tile) != descriptor
                || WOOD.blockDescriptor.invoke(block) != blockDescriptor
                || WOOD.tileDescriptor.invoke(tile) != descriptor
                || WOOD.descriptorMovement.invoke(descriptor) != movement
                || WOOD.descriptorMovement.invoke(blockDescriptor) != movement
                || (Boolean) API.moving.invoke(tile)
                || WOOD.state.invoke(tile) != state
                || (Boolean) WOOD.centered.invoke(tile) != centered) return sample(Result.UNKNOWN);
            return new Sample(
                Result.SAFE,
                body,
                new DoorCell(
                    DoorCell.MALISIS_WOOD,
                    x,
                    bottomY,
                    z,
                    y,
                    metadata,
                    lowerMetadata,
                    upperMetadata,
                    centered,
                    opened));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, WOOD_FEATURE, failure);
            return sample(Result.UNKNOWN);
        }
    }

    private static boolean woodTile(TileEntity tile, World world, int x, int y, int z) {
        return tile != null && tile.getClass() == WOOD.tile
            && tile.getWorldObj() == world
            && tile.xCoord == x
            && tile.yCoord == y
            && tile.zCoord == z
            && !tile.isInvalid();
    }

    private static boolean withinCell(AxisAlignedBB b, int x, int y, int z) {
        return Double.isFinite(b.minX) && Double.isFinite(b.minY)
            && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX)
            && Double.isFinite(b.maxY)
            && Double.isFinite(b.maxZ)
            && b.minX >= x
            && b.minY >= y
            && b.minZ >= z
            && b.maxX <= x + 1
            && b.maxY <= y + 1
            && b.maxZ <= z + 1
            && b.minX < b.maxX
            && b.minY < b.maxY
            && b.minZ < b.maxZ;
    }

    private static Wood wood() {
        if (API == null) return null;
        try {
            return new Wood(MalisisCombatGeometry.class.getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, WOOD_FEATURE, failure);
            return null;
        }
    }

    private static Sample sample(Result result) {
        return new Sample(result, null);
    }

    private static Api api() {
        var mod = Loader.instance()
            .getIndexedModList()
            .get(MalisisDoorsCompat.MOD_ID);
        if (mod == null || !MalisisDoorsCompat.REVIEWED_VERSION.equals(mod.getVersion())) return null;
        try {
            var loader = MalisisCombatGeometry.class.getClassLoader();
            Class<?> gate = Class.forName("net.malisis.doors.door.block.FenceGate", false, loader);
            Class<?> tile = Class.forName("net.malisis.doors.door.tileentity.FenceGateTileEntity", false, loader);
            Class<?> base = Class.forName("net.malisis.doors.door.tileentity.DoorTileEntity", false, loader);
            Method moving = tile.getMethod("isMoving"), opened = tile.getMethod("isOpened");
            if (gate.getSuperclass() != BlockFenceGate.class || tile.getSuperclass() != base
                || moving.getDeclaringClass() != base
                || opened.getDeclaringClass() != base) return null;
            return new Api(gate, tile, moving, opened);
        } catch (ReflectiveOperationException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, FEATURE, failure);
            return null;
        }
    }

    private static final class Wood {

        final Class<?> door, tile, descriptor, vanilla, rotating;
        final Method blockDescriptor, tileDescriptor, descriptorMovement, descriptorBlock, descriptorTile;
        final Method state, centered, bounds, direction, rotate;
        final Field rawDescriptor;
        final Object collisionType;

        Wood(ClassLoader loader) throws ReflectiveOperationException {
            door = Class.forName("net.malisis.doors.door.block.Door", false, loader);
            tile = Class.forName("net.malisis.doors.door.tileentity.DoorTileEntity", false, loader);
            descriptor = Class.forName("net.malisis.doors.door.DoorDescriptor", false, loader);
            vanilla = Class.forName("net.malisis.doors.door.descriptor.VanillaDoor", false, loader);
            rotating = Class.forName("net.malisis.doors.door.movement.RotatingDoorMovement", false, loader);
            Class<?> boxType = Class.forName("net.malisis.core.block.BoundingBoxType", false, loader);
            Class<?> utilities = Class.forName("net.malisis.core.util.AABBUtils", false, loader);
            blockDescriptor = door.getMethod("getDescriptor");
            tileDescriptor = tile.getMethod("getDescriptor");
            descriptorMovement = descriptor.getMethod("getMovement");
            descriptorBlock = descriptor.getMethod("getBlock");
            descriptorTile = descriptor.getMethod("getTileEntityClass");
            state = tile.getMethod("getState");
            centered = tile.getMethod("isCentered");
            bounds = rotating.getMethod("getBoundingBox", tile, boolean.class, boxType);
            direction = door.getMethod("intToDir", int.class);
            rotate = utilities.getMethod("rotate", AxisAlignedBB.class, ForgeDirection.class);
            rawDescriptor = tile.getDeclaredField("descriptor");
            rawDescriptor.setAccessible(true);
            collisionType = boxType.getField("COLLISION")
                .get(null);
            if (door.getSuperclass() != BlockDoor.class || tile.getSuperclass() != TileEntity.class
                || vanilla.getSuperclass() != descriptor
                || blockDescriptor.getDeclaringClass() != door
                || tileDescriptor.getDeclaringClass() != tile
                || descriptorMovement.getDeclaringClass() != descriptor
                || vanilla.getMethod("getMovement")
                    .getDeclaringClass() != descriptor
                || vanilla.getMethod("getBlock")
                    .getDeclaringClass() != descriptor
                || vanilla.getMethod("getTileEntityClass")
                    .getDeclaringClass() != descriptor
                || descriptorBlock.getDeclaringClass() != descriptor
                || descriptorTile.getDeclaringClass() != descriptor
                || state.getDeclaringClass() != tile
                || centered.getDeclaringClass() != tile
                || bounds.getDeclaringClass() != rotating
                || direction.getDeclaringClass() != door
                || rotate.getDeclaringClass() != utilities
                || rawDescriptor.getType() != descriptor)
                throw new ReflectiveOperationException("Installed wooden-door provider ownership differs");
        }
    }

    private static final class Api {

        final Class<?> gate, tile;
        final Method moving, opened;

        Api(Class<?> gate, Class<?> tile, Method moving, Method opened) {
            this.gate = gate;
            this.tile = tile;
            this.moving = moving;
            this.opened = opened;
        }
    }
}
