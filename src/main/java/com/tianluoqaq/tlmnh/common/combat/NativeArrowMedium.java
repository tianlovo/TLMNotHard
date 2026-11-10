package com.tianluoqaq.tlmnh.common.combat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.compat.etfuturum.common.physics.NativeLiquidPhysics;

/** 一次预瞄的有界、只读水域快照；不移动箭、不推进世界、不加载区块 */
public final class NativeArrowMedium implements IBlockAccess {

    public static final String GUARD = "arrow_medium";
    private static final int MAX_CELLS = 4096;
    private final World world;
    private final net.minecraft.world.WorldProvider provider;
    private final EntityArrow arrow;
    private final int dimension, entityId;
    private final java.util.UUID identity;
    private final double originX, originY, originZ;
    private final BooleanSupplier authorized;
    private final Map<Point, Cell> cells = new LinkedHashMap<>();
    private final double minX, minY, minZ, maxX, maxY, maxZ;
    private final float width, height, yOffset, ySize;
    private final NativeLiquidPhysics.Sample liquidPhysics;

    public NativeArrowMedium(EntityArrow arrow, BooleanSupplier authorized) {
        this.arrow = arrow;
        world = arrow.worldObj;
        provider = world.provider;
        dimension = arrow.dimension;
        entityId = arrow.getEntityId();
        identity = arrow.getUniqueID();
        originX = arrow.posX;
        originY = arrow.posY;
        originZ = arrow.posZ;
        this.authorized = authorized;
        minX = arrow.boundingBox.minX - arrow.posX;
        minY = arrow.boundingBox.minY - arrow.posY;
        minZ = arrow.boundingBox.minZ - arrow.posZ;
        maxX = arrow.boundingBox.maxX - arrow.posX;
        maxY = arrow.boundingBox.maxY - arrow.posY;
        maxZ = arrow.boundingBox.maxZ - arrow.posZ;
        width = arrow.width;
        height = arrow.height;
        yOffset = arrow.yOffset;
        ySize = arrow.ySize;
        liquidPhysics = NativeLiquidPhysics.sample();
    }

    public ArrowPhysics.Medium at(BallisticVector position) {
        requireCurrent();
        // 精确保留Entity.handleWaterMovement的expand(0,-float(.4),0).contract(.001)
        // 箭高.5时Y盒可能反向，不能擅自排序成另一种水判定
        int x0 = MathHelper.floor_double(position.x + minX + .001);
        int x1 = MathHelper.floor_double(position.x + maxX - .001 + 1);
        int y0 = MathHelper.floor_double(position.y + minY + (double) .4F + .001);
        int y1 = MathHelper.floor_double(position.y + maxY - (double) .4F - .001 + 1);
        int z0 = MathHelper.floor_double(position.z + minZ + .001);
        int z1 = MathHelper.floor_double(position.z + maxZ - .001 + 1);
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (!world.getChunkProvider()
                .chunkExists(cx, cz)) throw new Unavailable(MaidBowAttack.Status.UNLOADED);
        boolean water = false;
        var flow = Vec3.createVectorHelper(0, 0, 0);
        for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) {
            var block = getBlock(x, y, z);
            if (block.getMaterial() != Material.water) continue;
            if (block != Blocks.water && block != Blocks.flowing_water)
                throw new Unavailable(MaidBowAttack.Status.LIQUID);
            double surface = (double) ((float) (y + 1) - BlockLiquid.getLiquidHeightPercent(getBlockMetadata(x, y, z)));
            if (y1 < surface) continue;
            water = true;
            try {
                var velocity = liquidPhysics
                    .cellFlow((Vec3) Flow.METHOD.invoke(block, this, x, y, z), getBlockMetadata(x, y, z));
                flow.xCoord += velocity.xCoord;
                flow.yCoord += velocity.yCoord;
                flow.zCoord += velocity.zCoord;
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Unavailable unavailable) throw unavailable;
                throw new IllegalStateException("Native water flow query failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native water flow query is unavailable", failure);
            }
        }
        requireCurrent();
        var push = BallisticVector.ZERO;
        if (flow.lengthVector() > 0 && arrow.isPushedByWater()) {
            flow = liquidPhysics.normalizeTotal(flow);
            push = new BallisticVector(flow.xCoord * .014, flow.yCoord * .014, flow.zCoord * .014);
        }
        return new ArrowPhysics.Medium(water ? (double) .8F : (double) .99F, push);
    }

    public boolean isCurrent() {
        return geometryCurrent() && authorized.getAsBoolean() && environmentUnchanged();
    }

    public boolean environmentUnchanged() {
        if (!liquidPhysics.isCurrent()) return false;
        for (var entry : cells.entrySet()) {
            var p = entry.getKey();
            if (!loaded(p.x, p.z) || world.getBlock(p.x, p.y, p.z) != entry.getValue().block
                || world.getBlockMetadata(p.x, p.y, p.z) != entry.getValue().metadata) return false;
        }
        return true;
    }

    private void requireCurrent() {
        if (!geometryCurrent() || !liquidPhysics.isCurrent() || !authorized.getAsBoolean())
            throw new Unavailable(MaidBowAttack.Status.PHYSICS_UNAVAILABLE);
    }

    private boolean geometryCurrent() {
        return !arrow.isDead && arrow.worldObj == world
            && world.provider == provider
            && arrow.dimension == dimension
            && provider.dimensionId == dimension
            && arrow.getEntityId() == entityId
            && identity.equals(arrow.getUniqueID())
            && arrow.posX == originX
            && arrow.posY == originY
            && arrow.posZ == originZ
            && arrow.width == width
            && arrow.height == height
            && arrow.yOffset == yOffset
            && arrow.ySize == ySize;
    }

    private boolean loaded(int x, int z) {
        return world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    private Cell cell(int x, int y, int z) {
        // native flow在同一只读调用中只读block/meta；不在每个邻居上重复昂贵的目标LOS与库存许可
        // 完整许可仍由at前后及发射提交逐次复核
        if (!geometryCurrent()) throw new Unavailable(MaidBowAttack.Status.PHYSICS_UNAVAILABLE);
        if (!loaded(x, z)) throw new Unavailable(MaidBowAttack.Status.UNLOADED);
        var p = new Point(x, y, z);
        var cell = cells.get(p);
        if (cell != null) return cell;
        if (cells.size() >= MAX_CELLS) throw new Unavailable(MaidBowAttack.Status.QUERY_LIMIT);
        cell = new Cell(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z));
        cells.put(p, cell);
        return cell;
    }

    public Block getBlock(int x, int y, int z) {
        return cell(x, y, z).block;
    }

    public int getBlockMetadata(int x, int y, int z) {
        return cell(x, y, z).metadata;
    }

    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    public TileEntity getTileEntity(int x, int y, int z) {
        throw new IllegalStateException("Unexpected native water tile query");
    }

    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
        throw new IllegalStateException("Unexpected native water light query");
    }

    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        throw new IllegalStateException("Unexpected native water power query");
    }

    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        throw new IllegalStateException("Unexpected native water biome query");
    }

    public int getHeight() {
        return world.getHeight();
    }

    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        throw new IllegalStateException("Unexpected native water side query");
    }

    private static final class Cell {

        final Block block;
        final int metadata;

        Cell(Block block, int metadata) {
            this.block = block;
            this.metadata = metadata;
        }
    }

    public static final class Unavailable extends RuntimeException {

        public final MaidBowAttack.Status reason;

        public Unavailable(MaidBowAttack.Status reason) {
            super("Arrow medium: " + reason);
            this.reason = reason;
        }
    }

    private static final class Flow {

        static final Method METHOD = resolve();

        private static Method resolve() {
            Method found = null;
            for (var method : BlockLiquid.class.getDeclaredMethods()) {
                if (method.getReturnType() != Vec3.class || Modifier.isStatic(method.getModifiers())
                    || !java.util.Arrays.equals(
                        method.getParameterTypes(),
                        new Class<?>[] { IBlockAccess.class, int.class, int.class, int.class }))
                    continue;
                if (found != null) throw new IllegalStateException("Ambiguous native liquid flow signature");
                found = method;
            }
            if (found == null) throw new IllegalStateException("Native liquid flow signature unavailable");
            found.setAccessible(true);
            return found;
        }
    }
}
