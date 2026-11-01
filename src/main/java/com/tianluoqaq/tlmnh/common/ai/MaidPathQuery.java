package com.tianluoqaq.tlmnh.common.ai;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCache;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 有界原生查询的局部方块视图，不改全局寻路器或世界碰撞 */
public final class MaidPathQuery {

    private MaidPathQuery() {}

    public static PathEntity create(EntityMaid maid, int x, int y, int z, float range, boolean enterDoors,
        boolean closedDoors, boolean avoidWater, boolean swim) {
        var vertical = ladderPath(maid, x, y, z, range);
        if (vertical != null) return vertical;
        int ox = MathHelper.floor_double(maid.posX), oy = MathHelper.floor_double(maid.posY),
            oz = MathHelper.floor_double(maid.posZ);
        int pad = (int) (range + 8);
        var cache = new ChunkCache(maid.worldObj, ox - pad, oy - pad, oz - pad, ox + pad, oy + pad, oz + pad, 0);
        maid.worldObj.theProfiler.startSection("pathfind");
        try {
            return new PathFinder(cache, enterDoors, closedDoors, avoidWater, swim) {

                @Override
                public int getVerticalOffset(net.minecraft.entity.Entity actor, int bx, int by, int bz,
                    PathPoint size) {
                    boolean adapted = false;
                    for (int dx = 0; dx < size.xCoord; dx++)
                        for (int dy = 0; dy < size.yCoord; dy++) for (int dz = 0; dz < size.zCoord; dz++)
                            adapted |= passable(actor.worldObj.getBlock(bx + dx, by + dy, bz + dz));
                    if (!adapted) return super.getVerticalOffset(actor, bx, by, bz, size);
                    // 旧版静态判定直接读 entity.worldObj，替换 ChunkCache 不能改变门的判定
                    boolean special = false;
                    var unit = new PathPoint(1, 1, 1);
                    for (int dx = 0; dx < size.xCoord; dx++)
                        for (int dy = 0; dy < size.yCoord; dy++) for (int dz = 0; dz < size.zCoord; dz++) {
                            int px = bx + dx, py = by + dy, pz = bz + dz;
                            if (passable(actor.worldObj.getBlock(px, py, pz))) continue;
                            int result = super.getVerticalOffset(actor, px, py, pz, unit);
                            if (result <= 0) return result;
                            special |= result == 2;
                        }
                    return special ? 2 : 1;
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
        int tx = MathHelper.floor_double(x - maid.width / 2), tz = MathHelper.floor_double(z - maid.width / 2);
        if (targetY == cy || Math.abs(targetY - cy) > range || Math.abs(tx - cx) + Math.abs(tz - cz) > 1) return null;
        var points = new java.util.ArrayList<PathPoint>();
        int step = targetY > cy ? 1 : -1;
        for (int py = cy;; py += step) {
            var block = maid.worldObj.getBlock(cx, py, cz);
            if (!block.isLadder(maid.worldObj, cx, py, cz, maid)) return null;
            var box = net.minecraft.util.AxisAlignedBB
                .getBoundingBox(cx + 0.2, py, cz + 0.2, cx + 0.8, py + maid.height, cz + 0.8);
            if (!maid.worldObj.getCollidingBoundingBoxes(maid, box)
                .isEmpty()) return null;
            points.add(new PathPoint(cx, py, cz));
            if (py == targetY) break;
        }
        if (tx != cx || tz != cz) {
            if (!maid.worldObj.getBlock(tx, targetY - 1, tz)
                .getMaterial()
                .blocksMovement()) return null;
            var exit = net.minecraft.util.AxisAlignedBB
                .getBoundingBox(tx + 0.2, targetY, tz + 0.2, tx + 0.8, targetY + maid.height, tz + 0.8);
            if (!maid.worldObj.getCollidingBoundingBoxes(maid, exit)
                .isEmpty()) return null;
            points.add(new PathPoint(tx, targetY, tz));
        }
        return new PathEntity(points.toArray(new PathPoint[0]));
    }
}
