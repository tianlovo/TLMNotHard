package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

import java.util.function.BooleanSupplier;

import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 只观察原路线下一步的实际方块碰撞，不加载区块或扫描替代路线 */
public final class MaidRouteBlockage {

    private MaidRouteBlockage() {}

    public static BooleanSupplier observe(EntityMaid maid, PathEntity route, BooleanSupplier authorized) {
        if (!authorized.getAsBoolean() || route == null || route.isFinished()) return null;
        var world = maid.worldObj;
        var provider = world.provider;
        int dimension = maid.dimension;
        var identity = maid.getUniqueID();
        double x = maid.posX, y = maid.posY, z = maid.posZ;
        float width = maid.width, height = maid.height;
        var body = maid.boundingBox.copy();
        var next = route.getPosition(maid);
        double dx = next.xCoord - x, dy = next.yCoord - maid.boundingBox.minY, dz = next.zCoord - z;
        if (!Double.isFinite(dx) || !Double.isFinite(dy)
            || !Double.isFinite(dz)
            || Math.abs(dx) > NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM
            || Math.abs(dy) > NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP
            || Math.abs(dz) > NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM) return null;
        var corridor = body.addCoord(dx, dy, dz);
        if (!Double.isFinite(corridor.minX) || !Double.isFinite(corridor.minY)
            || !Double.isFinite(corridor.minZ)
            || !Double.isFinite(corridor.maxX)
            || !Double.isFinite(corridor.maxY)
            || !Double.isFinite(corridor.maxZ)) return null;
        // 对应World.func_147461_a的精确循环范围，包含底层栅栏可延伸到身体内的一层
        long cells = (long) (MathHelper.floor_double(corridor.maxX + 1) - MathHelper.floor_double(corridor.minX))
            * (MathHelper.floor_double(corridor.maxY + 1) - MathHelper.floor_double(corridor.minY) + 1)
            * (MathHelper.floor_double(corridor.maxZ + 1) - MathHelper.floor_double(corridor.minZ));
        if (cells <= 0 || cells > NavigationEvalTuning.MAX_COLLISION_CELLS) return null;
        var region = new MaidNativePathRegion(world, maid);
        BooleanSupplier sameInput = () -> maid.worldObj == world && world.provider == provider
            && maid.dimension == dimension
            && provider.dimensionId == dimension
            && identity.equals(maid.getUniqueID())
            && world.getEntityByID(maid.getEntityId()) == maid
            && maid.posX == x
            && maid.posY == y
            && maid.posZ == z
            && maid.width == width
            && maid.height == height
            && maid.boundingBox.minX == body.minX
            && maid.boundingBox.minY == body.minY
            && maid.boundingBox.minZ == body.minZ
            && maid.boundingBox.maxX == body.maxX
            && maid.boundingBox.maxY == body.maxY
            && maid.boundingBox.maxZ == body.maxZ;
        if (!sameInput.getAsBoolean() || !loaded(world, corridor)
            || !region
                .collides(corridor.minX, corridor.minY, corridor.minZ, corridor.maxX, corridor.maxY, corridor.maxZ)
            || !authorized.getAsBoolean()
            || !sameInput.getAsBoolean()
            || !loaded(world, corridor)) return null;
        return () -> {
            if (!authorized.getAsBoolean() || !sameInput.getAsBoolean() || !loaded(world, corridor)) return false;
            // 未加载的区块由region视作碰撞，不能以不可读输入推断道路已经清空
            boolean clear = !region
                .collides(corridor.minX, corridor.minY, corridor.minZ, corridor.maxX, corridor.maxY, corridor.maxZ);
            return authorized.getAsBoolean() && sameInput.getAsBoolean() && loaded(world, corridor) && clear;
        };
    }

    private static boolean loaded(World world, AxisAlignedBB corridor) {
        int firstX = MathHelper.floor_double(corridor.minX)
            >> NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2,
            lastX = MathHelper.floor_double(corridor.maxX)
                >> NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2;
        int firstZ = MathHelper.floor_double(corridor.minZ)
            >> NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2,
            lastZ = MathHelper.floor_double(corridor.maxZ)
                >> NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_COST_SECOND_PARAM_2;
        for (int x = firstX; x <= lastX; x++) for (int z = firstZ; z <= lastZ; z++) if (!world.getChunkProvider()
            .chunkExists(x, z)) return false;
        return true;
    }
}
