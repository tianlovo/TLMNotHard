package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType.*;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/** 正式版方块能力适配，碰撞仅查方块，不将其他演员当作静态寻路墙 */
public final class MaidNativePathRegion implements MaidPathRegion {

    private final World world;

    public MaidNativePathRegion(World world) {
        this.world = world;
    }

    private boolean loaded(int x, int y, int z) {
        return y >= minY() && y < maxY()
            && world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4);
    }

    @Override
    public MaidPathType rawType(int x, int y, int z) {
        if (!loaded(x, y, z)) return BLOCKED;
        Block block = world.getBlock(x, y, z);
        if (block.isAir(world, x, y, z)) return OPEN;
        if (block instanceof BlockTrapDoor || block == Blocks.waterlily) return TRAPDOOR;
        if (block == Blocks.cactus) return DAMAGE_OTHER;
        String name = block.getClass()
            .getName();
        if (name.equals("ganymedes01.etfuturum.blocks.BlockHoney")) return STICKY_HONEY;
        if (name.equals("ganymedes01.etfuturum.blocks.BlockWitherRose")) return DAMAGE_CAUTIOUS;
        if (block == Blocks.cocoa) return COCOA;
        Material material = block.getMaterial();
        if (material == Material.lava) return LAVA;
        if (block == Blocks.fire || name.equals("ganymedes01.etfuturum.blocks.BlockMagma")) return DAMAGE_FIRE;
        if (block instanceof BlockDoor) return block.getBlocksMovement(world, x, y, z) ? DOOR_OPEN
            : material == Material.wood ? DOOR_WOOD_CLOSED : DOOR_IRON_CLOSED;
        if (block instanceof BlockRailBase) return RAIL;
        if (block instanceof BlockLeavesBase) return LEAVES;
        if (block instanceof BlockFence || block instanceof BlockWall
            || block instanceof BlockFenceGate && (world.getBlockMetadata(x, y, z) & 4) == 0) return FENCE;
        if (!block.getBlocksMovement(world, x, y, z)) return BLOCKED;
        return material == Material.water ? WATER : OPEN;
    }

    @Override
    public boolean water(int x, int y, int z) {
        return loaded(x, y, z) && world.getBlock(x, y, z)
            .getMaterial() == Material.water;
    }

    @Override
    public boolean fluidEmpty(int x, int y, int z) {
        return loaded(x, y, z) && !world.getBlock(x, y, z)
            .getMaterial()
            .isLiquid();
    }

    @Override
    public boolean rail(int x, int y, int z) {
        return loaded(x, y, z) && world.getBlock(x, y, z) instanceof BlockRailBase;
    }

    @Override
    public double floor(int x, int y, int z) {
        if (!loaded(x, y - 1, z)) return y - 1;
        var block = world.getBlock(x, y - 1, z);
        block.setBlockBoundsBasedOnState(world, x, y - 1, z);
        var boxes = new java.util.ArrayList<AxisAlignedBB>();
        block.addCollisionBoxesToList(
            world,
            x,
            y - 1,
            z,
            AxisAlignedBB.getBoundingBox(x, y - 1, z, x + 1, y + 1, z + 1),
            boxes,
            null);
        double top = y - 1;
        for (var box : boxes) top = Math.max(top, box.maxY);
        return top;
    }

    @Override
    public boolean collides(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int firstX = (int) Math.floor(minX) >> 4, lastX = (int) Math.floor(maxX) >> 4;
        int firstZ = (int) Math.floor(minZ) >> 4, lastZ = (int) Math.floor(maxZ) >> 4;
        for (int x = firstX; x <= lastX; x++) for (int z = firstZ; z <= lastZ; z++) if (!world.getChunkProvider()
            .chunkExists(x, z)) return true;
        return !world.func_147461_a(AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ))
            .isEmpty();
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        return world.getHeight();
    }

    @Override
    public int seaLevel() {
        // 旧World没有现代生成器海平面API，当前主世界映射为63；自定义维度另适配
        return 63;
    }
}
