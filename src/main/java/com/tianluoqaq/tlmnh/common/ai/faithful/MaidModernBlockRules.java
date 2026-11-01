package com.tianluoqaq.tlmnh.common.ai.faithful;

import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/** 现代BlockState默认legacySolid碰撞形状公式，特殊forceSolid元数据仍需专门适配 */
final class MaidModernBlockRules {

    private MaidModernBlockRules() {}

    static boolean solid(World world, int x, int y, int z) {
        var block = world.getBlock(x, y, z);
        block.setBlockBoundsBasedOnState(world, x, y, z);
        var box = block.getCollisionBoundingBoxFromPool(world, x, y, z);
        if (box == null) return false;
        double sx = box.maxX - box.minX, sy = box.maxY - box.minY, sz = box.maxZ - box.minZ;
        return (sx + sy + sz) / 3 >= .7291666666666666D || sy >= 1;
    }

    static boolean blocksMotion(World world, int x, int y, int z) {
        return world.getBlock(x, y, z) != Blocks.web && solid(world, x, y, z);
    }
}
