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

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrainIds;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.malisisdoors.MalisisDoorsCompat;
import com.tianluoqaq.tlmnh.compat.malisisdoors.MalisisDoorsIds;

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

    private static final Api API = api();
    private static final Wood WOOD = wood();

    private MalisisCombatGeometry() {}

    public static boolean matches(Block block) {
        return API != null && (block == Blocks.fence_gate && block.getClass() == API.gate
            && IntegrationGuard.available(Side.SERVER, MalisisDoorsCombatIds.FEATURE)
            || WOOD != null && block == Blocks.wooden_door
                && block.getClass() == WOOD.door
                && IntegrationGuard.available(Side.SERVER, MalisisDoorsCombatIds.WOOD_FEATURE));
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
            .chunkExists(
                x >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT,
                z >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT))
            return sample(Result.UNKNOWN);
        var chunk = world.getChunkFromChunkCoords(
            x >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT,
            z >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT);
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        TileEntity tile = chunk.chunkTileEntityMap.get(
            new ChunkPosition(
                x & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK,
                y,
                z & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK));
        if (tile == null || tile.getClass() != API.tile
            || tile.getWorldObj() != world
            || tile.xCoord != x
            || tile.yCoord != y
            || tile.zCoord != z
            || tile.isInvalid()) return sample(Result.UNKNOWN);
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        int metadata = world.getBlockMetadata(x, y, z);
        if (!quota.spend(Work.BLOCK, MalisisDoorsCombatTuning.MALISIS_COMBAT_SPEND_SECOND_PARAM))
            return sample(Result.BUDGET);
        try {
            // isMoving是字段读；isOpened可刷新原生缓存，已预付最多3个真实查询
            boolean moving = (Boolean) API.moving.invoke(tile);
            boolean opened = moving || (Boolean) API.opened.invoke(tile);
            if (opened || (metadata & MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT) != 0)
                return new Sample(Result.SAFE, null);
            AxisAlignedBB body = metadata == 0 || metadata == MalisisDoorsCombatTuning.TARGET_METADATA_THRESHOLD
                ? AxisAlignedBB.getBoundingBox(
                    x,
                    y,
                    (float) z + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_THIRD_FACTOR,
                    x + 1,
                    (float) y + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_FIFTH_FACTOR,
                    (float) z + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_SIXTH_FACTOR)
                : AxisAlignedBB.getBoundingBox(
                    (float) x + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_THIRD_FACTOR,
                    y,
                    z,
                    (float) x + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_SIXTH_FACTOR,
                    (float) y + MalisisDoorsCombatTuning.MALISIS_COMBAT_GET_BOUNDING_BOX_FIFTH_FACTOR,
                    z + 1);
            return new Sample(Result.SAFE, body);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, MalisisDoorsCombatIds.FEATURE, failure);
            return sample(Result.UNKNOWN);
        }
    }

    private static Sample captureWood(World world, Block block, int x, int y, int z, CombatWorkQuota quota) {
        if (!quota.spend(Work.CHUNK)) return sample(Result.BUDGET);
        if (!world.getChunkProvider()
            .chunkExists(
                x >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT,
                z >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT))
            return sample(Result.UNKNOWN);
        var chunk = world.getChunkFromChunkCoords(
            x >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT,
            z >> MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT);
        var tiles = chunk.chunkTileEntityMap;
        if (!quota.spend(Work.BLOCK)) return sample(Result.BUDGET);
        int queryMetadata = world.getBlockMetadata(x, y, z);
        int bottomY = (queryMetadata & MalisisDoorsCombatTuning.MALISIS_COMBAT_BIT_MASK_8) == 0 ? y : y - 1;
        if (bottomY < 0 || bottomY >= MalisisDoorsCombatTuning.MAX_BOTTOM_Y_THRESHOLD) return sample(Result.UNKNOWN);
        if (!quota.spend(Work.BLOCK, MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT)) return sample(Result.BUDGET);
        Block lower = world.getBlock(x, bottomY, z), upper = world.getBlock(x, bottomY + 1, z);
        int lowerMetadata = world.getBlockMetadata(x, bottomY, z),
            upperMetadata = world.getBlockMetadata(x, bottomY + 1, z);
        if (lower != block || upper != block
            || (lowerMetadata & ~MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_PARAM) != 0
            || (upperMetadata & ~MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_PARAM_2) != 0
            || (upperMetadata & MalisisDoorsCombatTuning.MALISIS_COMBAT_BIT_MASK_8) == 0
            || queryMetadata != (y == bottomY ? lowerMetadata : upperMetadata)) return sample(Result.UNKNOWN);
        var bottomKey = new ChunkPosition(
            x & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK,
            bottomY,
            z & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK);
        var topKey = new ChunkPosition(
            x & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK,
            bottomY + 1,
            z & MalisisDoorsCombatTuning.CHUNK_LOCAL_MASK);
        if (!quota.spend(Work.BLOCK, MalisisDoorsCombatTuning.TARGET_METADATA_THRESHOLD)) return sample(Result.BUDGET);
        TileEntity tile = tiles.get(bottomKey);
        if (!woodTile(tile, world, x, bottomY, z) || tiles.get(topKey) != null) return sample(Result.UNKNOWN);
        try {
            if (!quota.spend(Work.COLLISION, MalisisDoorsCombatTuning.MALISIS_COMBAT_BIT_MASK_8))
                return sample(Result.BUDGET);
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
            if (!quota.spend(Work.COLLISION, MalisisDoorsCombatTuning.MALISIS_COMBAT_SPEND_SECOND_PARAM_2))
                return sample(Result.BUDGET);
            if (WOOD.tileDescriptor.invoke(tile) != descriptor || (Boolean) API.moving.invoke(tile))
                return sample(Result.UNKNOWN);
            Object state = WOOD.state.invoke(tile);
            boolean centered = (Boolean) WOOD.centered.invoke(tile);
            if (!(state instanceof Enum<?>value) || !value.name()
                .equals(MalisisDoorsCombatIds.KEY_CLOSED)
                && !value.name()
                    .equals(MalisisDoorsCombatIds.KEY_OPENED))
                return sample(Result.UNKNOWN);
            if (!quota.spend(Work.BLOCK, MalisisDoorsCombatTuning.WOOD_METADATA_QUERIES)) return sample(Result.BUDGET);
            int metadata = tile.getBlockMetadata();
            int expected = lowerMetadata & MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_PARAM
                | ((upperMetadata & MalisisDoorsCombatTuning.MALISIS_COMBAT_BIT_MASK_1) != 0
                    ? MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_PARAM_3
                    : MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_ZERO_BASE);
            boolean opened = value.name()
                .equals(MalisisDoorsCombatIds.KEY_OPENED);
            if (metadata != expected || ((metadata & MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT) != 0) != opened)
                return sample(Result.UNKNOWN);
            // 原生Rotating入口内部最多两次getBlockMetadata；预付其所有嵌套查询
            if (!quota.spend(
                Work.BLOCK,
                MalisisDoorsCombatTuning.WOOD_METADATA_QUERIES * MalisisDoorsCombatTuning.TARGET_METADATA_THRESHOLD)
                || !quota.spend(Work.SHAPE, MalisisDoorsCombatTuning.MALISIS_COMBAT_SPEND_SECOND_PARAM))
                return sample(Result.BUDGET);
            Object produced = WOOD.bounds.invoke(movement, tile, y != bottomY, WOOD.collisionType);
            if (!(produced instanceof AxisAlignedBB body) || body.getClass() != AxisAlignedBB.class)
                return sample(Result.UNKNOWN);
            if (centered) body.offset(
                MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_ZERO_BASE,
                MalisisDoorsCombatTuning.MALISIS_COMBAT_CAPTURE_WOOD_ZERO_BASE,
                MalisisDoorsCombatTuning.MALISIS_COMBAT_OFFSET_THIRD_FACTOR);
            Object direction = WOOD.direction
                .invoke(null, metadata & MalisisDoorsCombatTuning.MALISIS_COMBAT_SPEND_SECOND_PARAM);
            if (WOOD.rotate.invoke(null, body, direction) != body) return sample(Result.UNKNOWN);
            body.offset(x, y, z);
            // centered opened可能越cell，现单格证明不能把该邻格默认为净空
            if (!withinCell(body, x, y, z)) return sample(Result.UNKNOWN);
            if (!quota.spend(
                Work.BLOCK,
                MalisisDoorsCombatTuning.WOOD_METADATA_QUERIES + MalisisDoorsCombatTuning.CHUNK_SECTION_SHIFT
                    + MalisisDoorsCombatTuning.TARGET_METADATA_THRESHOLD)
                || !quota.spend(Work.COLLISION, MalisisDoorsCombatTuning.MALISIS_COMBAT_SPEND_SECOND_PARAM_3))
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
                    CombatTerrainIds.MALISIS_WOOD,
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
            IntegrationGuard.report(Side.SERVER, MalisisDoorsCombatIds.WOOD_FEATURE, failure);
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
            IntegrationGuard.report(Side.SERVER, MalisisDoorsCombatIds.WOOD_FEATURE, failure);
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
        if (mod == null || !MalisisDoorsIds.REVIEWED_VERSION.equals(mod.getVersion())) return null;
        try {
            var loader = MalisisCombatGeometry.class.getClassLoader();
            Class<?> gate = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_FENCE_GATE, false, loader);
            Class<?> tile = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_FENCE_GATE_TILE_ENTITY, false, loader);
            Class<?> base = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_DOOR_TILE_ENTITY, false, loader);
            Method moving = tile.getMethod(MalisisDoorsCombatIds.ID_ISMOVING),
                opened = tile.getMethod(MalisisDoorsCombatIds.ID_ISOPENED);
            if (gate.getSuperclass() != BlockFenceGate.class || tile.getSuperclass() != base
                || moving.getDeclaringClass() != base
                || opened.getDeclaringClass() != base) return null;
            return new Api(gate, tile, moving, opened);
        } catch (ReflectiveOperationException | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, MalisisDoorsCombatIds.FEATURE, failure);
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
            door = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_DOOR, false, loader);
            tile = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_DOOR_TILE_ENTITY, false, loader);
            descriptor = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_DOOR_DESCRIPTOR, false, loader);
            vanilla = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_VANILLA_DOOR, false, loader);
            rotating = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_ROTATING_DOOR_MOVEMENT, false, loader);
            Class<?> boxType = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_BOUNDING_BOX_TYPE, false, loader);
            Class<?> utilities = Class.forName(MalisisDoorsCombatIds.TARGET_CLASS_AABB_UTILS, false, loader);
            blockDescriptor = door.getMethod(MalisisDoorsCombatIds.ID_GETDESCRIPTOR);
            tileDescriptor = tile.getMethod(MalisisDoorsCombatIds.ID_GETDESCRIPTOR);
            descriptorMovement = descriptor.getMethod(MalisisDoorsCombatIds.ID_GETMOVEMENT);
            descriptorBlock = descriptor.getMethod(MalisisDoorsCombatIds.ID_GETBLOCK);
            descriptorTile = descriptor.getMethod(MalisisDoorsCombatIds.ID_GETTILEENTITYCLASS);
            state = tile.getMethod(MalisisDoorsCombatIds.ID_GETSTATE);
            centered = tile.getMethod(MalisisDoorsCombatIds.ID_ISCENTERED);
            bounds = rotating.getMethod(MalisisDoorsCombatIds.ID_GETBOUNDINGBOX, tile, boolean.class, boxType);
            direction = door.getMethod(MalisisDoorsCombatIds.ID_INTTODIR, int.class);
            rotate = utilities.getMethod(MalisisDoorsCombatIds.ID_ROTATE, AxisAlignedBB.class, ForgeDirection.class);
            rawDescriptor = tile.getDeclaredField(MalisisDoorsCombatIds.ID_DESCRIPTOR);
            rawDescriptor.setAccessible(true);
            collisionType = boxType.getField(MalisisDoorsCombatIds.KEY_COLLISION)
                .get(null);
            if (door.getSuperclass() != BlockDoor.class || tile.getSuperclass() != TileEntity.class
                || vanilla.getSuperclass() != descriptor
                || blockDescriptor.getDeclaringClass() != door
                || tileDescriptor.getDeclaringClass() != tile
                || descriptorMovement.getDeclaringClass() != descriptor
                || vanilla.getMethod(MalisisDoorsCombatIds.ID_GETMOVEMENT)
                    .getDeclaringClass() != descriptor
                || vanilla.getMethod(MalisisDoorsCombatIds.ID_GETBLOCK)
                    .getDeclaringClass() != descriptor
                || vanilla.getMethod(MalisisDoorsCombatIds.ID_GETTILEENTITYCLASS)
                    .getDeclaringClass() != descriptor
                || descriptorBlock.getDeclaringClass() != descriptor
                || descriptorTile.getDeclaringClass() != descriptor
                || state.getDeclaringClass() != tile
                || centered.getDeclaringClass() != tile
                || bounds.getDeclaringClass() != rotating
                || direction.getDeclaringClass() != door
                || rotate.getDeclaringClass() != utilities
                || rawDescriptor.getType() != descriptor)
                throw new ReflectiveOperationException(
                    MalisisDoorsCombatDiagnostics.MSG_INSTALLED_WOODEN_DOOR_PROVIDER_OWNERSHIP);
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
