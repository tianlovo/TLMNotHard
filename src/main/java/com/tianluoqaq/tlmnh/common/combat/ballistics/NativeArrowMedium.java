package com.tianluoqaq.tlmnh.common.combat.ballistics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;

/** 一次预瞄的有界、只读水域快照；不移动箭、不推进世界、不加载区块 */
public final class NativeArrowMedium implements IBlockAccess {

    private final World world;
    private final WorldProvider provider;
    private final EntityArrow arrow;
    private final int dimension, entityId;
    private final UUID identity;
    private final double originX, originY, originZ;
    private final BooleanSupplier authorized;
    private final Map<Point, Cell> cells = new LinkedHashMap<>();
    private final double minX, minY, minZ, maxX, maxY, maxZ;
    private final float width, height, yOffset, ySize;
    private final ProjectilePhysicsProvider.LiquidFlowSample liquidPhysics;

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
        liquidPhysics = CompatServices.projectilePhysics()
            .sampleLiquidFlow();
    }

    public ArrowPhysics.Medium at(BallisticVector position) {
        requireCurrent();
        // 精确保留Entity.handleWaterMovement的expand(0,-float(.4),0).contract(.001)
        // 箭高.5时Y盒可能反向，不能擅自排序成另一种水判定
        int x0 = MathHelper.floor_double(position.x + minX + BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET);
        int x1 = MathHelper.floor_double(position.x + maxX - BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET + 1);
        int y0 = MathHelper.floor_double(
            position.y + minY
                + (double) BallisticsTuning.ARROW_MEDIUM_FLOOR_DOUBLE_FIRST_FACTOR
                + BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET);
        int y1 = MathHelper.floor_double(
            position.y + maxY
                - (double) BallisticsTuning.ARROW_MEDIUM_FLOOR_DOUBLE_FIRST_FACTOR
                - BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET
                + 1);
        int z0 = MathHelper.floor_double(position.z + minZ + BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET);
        int z1 = MathHelper.floor_double(position.z + maxZ - BallisticsTuning.ARROW_MEDIUM_AT_X0_OFFSET + 1);
        for (int cx = x0 >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT; cx
            <= x1 >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT; cx++)
            for (int cz = z0 >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT; cz
                <= z1 >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT; cz++)
                if (!world.getChunkProvider()
                    .chunkExists(cx, cz)) throw new Unavailable(MaidBowAttack.Status.UNLOADED);
        boolean water = false;
        var flow = Vec3.createVectorHelper(
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE);
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
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Unavailable unavailable) throw unavailable;
                throw new IllegalStateException(
                    BallisticsDiagnostics.MSG_NATIVE_WATER_FLOW_QUERY_FAILED,
                    failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(BallisticsDiagnostics.MSG_NATIVE_WATER_FLOW_QUERY_IS, failure);
            }
        }
        requireCurrent();
        var push = BallisticVector.ZERO;
        if (flow.lengthVector() > 0 && arrow.isPushedByWater()) {
            flow = liquidPhysics.normalizeTotal(flow);
            push = new BallisticVector(
                flow.xCoord * BallisticsTuning.ARROW_MEDIUM_PUSH,
                flow.yCoord * BallisticsTuning.ARROW_MEDIUM_PUSH,
                flow.zCoord * BallisticsTuning.ARROW_MEDIUM_PUSH);
        }
        return new ArrowPhysics.Medium(
            water ? (double) BallisticsTuning.ARROW_INTERCEPT_DOMAIN_FACTOR
                : (double) BallisticsTuning.ARROW_MEDIUM_MEDIUM_FIRST_FACTOR,
            push);
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
            .chunkExists(
                x >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT,
                z >> BallisticsTuning.ARROW_INTERCEPT_DISCRIMINANT);
    }

    private Cell cell(int x, int y, int z) {
        // native flow在同一只读调用中只读block/meta；不在每个邻居上重复昂贵的目标LOS与库存许可
        // 完整许可仍由at前后及发射提交逐次复核
        if (!geometryCurrent()) throw new Unavailable(MaidBowAttack.Status.PHYSICS_UNAVAILABLE);
        if (!loaded(x, z)) throw new Unavailable(MaidBowAttack.Status.UNLOADED);
        var p = new Point(x, y, z);
        var cell = cells.get(p);
        if (cell != null) return cell;
        if (cells.size() >= BallisticsTuning.MAX_CELLS) throw new Unavailable(MaidBowAttack.Status.QUERY_LIMIT);
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
        throw new IllegalStateException(BallisticsDiagnostics.MSG_UNEXPECTED_NATIVE_WATER_TILE_QUERY);
    }

    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
        throw new IllegalStateException(BallisticsDiagnostics.MSG_UNEXPECTED_NATIVE_WATER_LIGHT_QUERY);
    }

    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        throw new IllegalStateException(BallisticsDiagnostics.MSG_UNEXPECTED_NATIVE_WATER_POWER_QUERY);
    }

    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        throw new IllegalStateException(BallisticsDiagnostics.MSG_UNEXPECTED_NATIVE_WATER_BIOME_QUERY);
    }

    public int getHeight() {
        return world.getHeight();
    }

    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        throw new IllegalStateException(BallisticsDiagnostics.MSG_UNEXPECTED_NATIVE_WATER_SIDE_QUERY);
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
            super(BallisticsDiagnostics.MSG_ARROW_MEDIUM + reason);
            this.reason = reason;
        }
    }

    private static final class Flow {

        static final Method METHOD = resolve();

        private static Method resolve() {
            Method found = null;
            for (var method : BlockLiquid.class.getDeclaredMethods()) {
                if (method.getReturnType() != Vec3.class || Modifier.isStatic(method.getModifiers())
                    || !Arrays.equals(
                        method.getParameterTypes(),
                        new Class<?>[] { IBlockAccess.class, int.class, int.class, int.class }))
                    continue;
                if (found != null)
                    throw new IllegalStateException(BallisticsDiagnostics.MSG_AMBIGUOUS_NATIVE_LIQUID_FLOW_SIGNATURE);
                found = method;
            }
            if (found == null)
                throw new IllegalStateException(BallisticsDiagnostics.MSG_NATIVE_LIQUID_FLOW_SIGNATURE_UNAVAILABLE);
            found.setAccessible(true);
            return found;
        }
    }
}
