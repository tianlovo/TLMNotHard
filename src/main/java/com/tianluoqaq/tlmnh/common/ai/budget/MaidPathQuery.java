package com.tianluoqaq.tlmnh.common.ai.budget;

import java.util.ArrayList;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCache;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;

/** 有界原生查询的局部方块视图，不改全局寻路器或世界碰撞 */
public final class MaidPathQuery {

    private MaidPathQuery() {}

    public static PathEntity create(EntityMaid maid, int x, int y, int z, float range, boolean enterDoors,
        boolean closedDoors, boolean avoidWater, boolean swim) {
        if (maid.getNavigator() instanceof MaidPathNavigator navigator)
            return navigator.createPath(x, y, z, range, AiBudgetTuning.NAVIGATION_BUDGET_USED);
        var vertical = ladderPath(maid, x, y, z, range);
        if (vertical != null) return vertical;
        int ox = MathHelper.floor_double(maid.posX), oy = MathHelper.floor_double(maid.posY),
            oz = MathHelper.floor_double(maid.posZ);
        int pad = (int) (range + AiBudgetTuning.PATH_QUERY_PAD);
        var cache = new ChunkCache(
            maid.worldObj,
            ox - pad,
            oy - pad,
            oz - pad,
            ox + pad,
            oy + pad,
            oz + pad,
            AiBudgetTuning.NAVIGATION_BUDGET_USED);
        maid.worldObj.theProfiler.startSection(AiBudgetIds.ID_PATHFIND);
        try {
            return new PathFinder(cache, enterDoors, closedDoors, avoidWater, swim) {

                @Override
                public int getVerticalOffset(Entity actor, int bx, int by, int bz, PathPoint size) {
                    boolean adapted = false;
                    for (int dx = 0; dx < size.xCoord; dx++)
                        for (int dy = 0; dy < size.yCoord; dy++) for (int dz = 0; dz < size.zCoord; dz++)
                            adapted |= passable(actor.worldObj.getBlock(bx + dx, by + dy, bz + dz));
                    if (!adapted) return super.getVerticalOffset(actor, bx, by, bz, size);
                    // 旧版静态判定直接读 entity.worldObj，替换 ChunkCache 不能改变门的判定
                    boolean special = false;
                    var unit = new PathPoint(
                        AiBudgetTuning.MIN_LIMIT_THRESHOLD,
                        AiBudgetTuning.MIN_LIMIT_THRESHOLD,
                        AiBudgetTuning.MIN_LIMIT_THRESHOLD);
                    for (int dx = 0; dx < size.xCoord; dx++)
                        for (int dy = 0; dy < size.yCoord; dy++) for (int dz = 0; dz < size.zCoord; dz++) {
                            int px = bx + dx, py = by + dy, pz = bz + dz;
                            if (passable(actor.worldObj.getBlock(px, py, pz))) continue;
                            int result = super.getVerticalOffset(actor, px, py, pz, unit);
                            if (result <= 0) return result;
                            special |= result == AiBudgetTuning.TARGET_RESULT_THRESHOLD;
                        }
                    return special ? AiBudgetTuning.TARGET_RESULT_THRESHOLD : AiBudgetTuning.MIN_LIMIT_THRESHOLD;
                }

                private boolean passable(Block block) {
                    return block instanceof BlockFenceGate && maid.getConfigManager()
                        .isOpenFenceGate()
                        || block instanceof BlockDoor && block.getMaterial() == Material.wood
                            && enterDoors
                            && closedDoors
                            && maid.getConfigManager()
                                .isOpenDoor();
                }
            }.createEntityPathTo(maid, x, y, z, range);
        } finally {
            maid.worldObj.theProfiler.endSection();
        }
    }

    /** 已在梯柱上时补足原生寻路缺少的竖直边，远处找梯仍由后续导航扩展处理 */
    private static PathEntity ladderPath(EntityMaid maid, int x, int targetY, int z, float range) {
        if (!maid.getConfigManager()
            .isActiveClimbing() || !maid.isOnLadder()) return null;
        int cx = MathHelper.floor_double(maid.posX), cy = MathHelper.floor_double(maid.boundingBox.minY),
            cz = MathHelper.floor_double(maid.posZ);
        int tx = MathHelper
            .floor_double(x + AiBudgetTuning.CENTER_OFFSET_D - maid.width / AiBudgetTuning.TARGET_RESULT_THRESHOLD),
            tz = MathHelper
                .floor_double(z + AiBudgetTuning.CENTER_OFFSET_D - maid.width / AiBudgetTuning.TARGET_RESULT_THRESHOLD);
        if (targetY == cy || Math.abs(targetY - cy) > range
            || Math.abs(tx - cx) + Math.abs(tz - cz) > AiBudgetTuning.MIN_LIMIT_THRESHOLD) return null;
        var points = new ArrayList<PathPoint>();
        int step = targetY > cy ? AiBudgetTuning.MIN_LIMIT_THRESHOLD : AiBudgetTuning.UNSET_OR_NOT_FOUND;
        for (int py = cy;; py += step) {
            var block = maid.worldObj.getBlock(cx, py, cz);
            if (!block.isLadder(maid.worldObj, cx, py, cz, maid)) return null;
            var box = AxisAlignedBB.getBoundingBox(
                cx + AiBudgetTuning.PATH_QUERY_BOX,
                py,
                cz + AiBudgetTuning.PATH_QUERY_BOX,
                cx + AiBudgetTuning.PATH_QUERY_BOX_2,
                py + maid.height,
                cz + AiBudgetTuning.PATH_QUERY_BOX_2);
            if (!maid.worldObj.getCollidingBoundingBoxes(maid, box)
                .isEmpty()) return null;
            points.add(new PathPoint(cx, py, cz));
            if (py == targetY) break;
        }
        if (tx != cx || tz != cz) {
            if (!maid.worldObj.getBlock(tx, targetY - 1, tz)
                .getMaterial()
                .blocksMovement()) return null;
            var exit = AxisAlignedBB.getBoundingBox(
                tx + AiBudgetTuning.PATH_QUERY_BOX,
                targetY,
                tz + AiBudgetTuning.PATH_QUERY_BOX,
                tx + AiBudgetTuning.PATH_QUERY_BOX_2,
                targetY + maid.height,
                tz + AiBudgetTuning.PATH_QUERY_BOX_2);
            if (!maid.worldObj.getCollidingBoundingBoxes(maid, exit)
                .isEmpty()) return null;
            points.add(new PathPoint(tx, targetY, tz));
        }
        return new PathEntity(points.toArray(new PathPoint[0]));
    }
}
