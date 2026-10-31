package com.tianluoqaq.tlmnh.common.ai;

import java.util.function.BooleanSupplier;

import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRun;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** 参考固定上游 TeleportHelper 的家园候选；只采用已加载、可站立且无碰撞/液体的同维度落点 */
public final class MaidHomeTeleport {

    public enum Result {
        SUCCESS,
        NO_SAFE_DESTINATION,
        STALE
    }

    private MaidHomeTeleport() {}

    public static Result tryReturn(EntityMaid maid, MaidActionRunner runner, MaidActionRun run) {
        var home = maid.getHomeArea();
        var settings = home.settings();
        var activity = home.activity();
        var center = home.center();
        var world = maid.worldObj;
        double ox = maid.posX, oy = maid.posY, oz = maid.posZ;
        float width = maid.width, height = maid.height;
        var originalBox = maid.boundingBox.copy();
        BooleanSupplier lease = () -> world == maid.worldObj && home.enabled()
            && home.sameDimension()
            && home.settings() == settings
            && home.activity() == activity
            && maid.isEntityAlive()
            && !maid.isSitting()
            && maid.ridingEntity == null
            && maid.riddenByEntity == null
            && !maid.getLeashed()
            && !maid.hasPose(MaidPose.SLEEPING)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && runner.getOwner() == Owner.HOME
            && runner.isOwned(run, world, maid.getTask())
            && maid.width == width
            && maid.height == height;
        BooleanSupplier current = () -> lease.getAsBoolean() && maid.posX == ox && maid.posY == oy && maid.posZ == oz;
        if (world.isRemote || !current.getAsBoolean()) return Result.STALE;
        for (int attempt = 0; attempt < 10; attempt++) {
            if (!current.getAsBoolean()) return Result.STALE;
            // 上游家园分支为 x±3、y向上0..3、z±3；不同于跟随主人的7/3/7序列
            int x = center.x + maid.getRNG()
                .nextInt(7) - 3;
            int y = center.y + maid.getRNG()
                .nextInt(4);
            int z = center.z + maid.getRNG()
                .nextInt(7) - 3;
            if (y < 1 || y + height > world.getHeight()) continue;
            var candidate = originalBox.getOffsetBoundingBox(x - ox, y - oy, z - oz);
            if (!loaded(world, candidate)) continue;
            while (y > 0 && !world.getBlock(x, y - 1, z)
                .getMaterial()
                .blocksMovement()) {
                if (!current.getAsBoolean()) return Result.STALE;
                y--;
            }
            if (y <= 0 || !home.contains(x, y, z)) continue;
            var ground = world.getBlock(x, y - 1, z);
            if (!World.doesBlockHaveSolidTopSurface(world, x, y - 1, z) || ground == Blocks.cactus
                || ground.isBurning(world, x, y - 1, z)) continue;
            candidate = originalBox.getOffsetBoundingBox(x - ox, y - oy, z - oz);
            if (!loaded(world, candidate) || !world.getCollidingBoundingBoxes(maid, candidate)
                .isEmpty()
                || world.isAnyLiquid(candidate)
                || !world.checkNoEntityCollision(candidate, maid)
                || burning(world, candidate)) continue;
            if (!current.getAsBoolean()) return Result.STALE;
            maid.setPositionAndUpdate(x, y, z);
            if (!lease.getAsBoolean() || maid.posX != x || maid.posY != y || maid.posZ != z) return Result.STALE;
            maid.motionX = maid.motionY = maid.motionZ = 0;
            maid.setJumping(false);
            world.playSoundEffect(ox, oy, oz, "mob.endermen.portal", 1, 1);
            maid.playSound("mob.endermen.portal", 1, 1);
            return Result.SUCCESS;
        }
        return current.getAsBoolean() ? Result.NO_SAFE_DESTINATION : Result.STALE;
    }

    private static boolean loaded(World world, AxisAlignedBB box) {
        // 原生碰撞查询会访问边缘邻格，先核其完整水平包围，不调用provideChunk
        for (int x = MathHelper.floor_double(box.minX - 1) >> 4, maxX = MathHelper.floor_double(box.maxX + 1) >> 4; x
            <= maxX; x++)
            for (int z = MathHelper.floor_double(box.minZ - 1) >> 4,
                maxZ = MathHelper.floor_double(box.maxZ + 1) >> 4; z <= maxZ; z++)
                if (!world.getChunkProvider()
                    .chunkExists(x, z)) return false;
        return true;
    }

    private static boolean burning(World world, AxisAlignedBB box) {
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX); x++)
            for (int y = MathHelper.floor_double(box.minY); y <= MathHelper.floor_double(box.maxY); y++)
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ); z++) {
                    var block = world.getBlock(x, y, z);
                    if (block == Blocks.cactus || block.isBurning(world, x, y, z)) return true;
                }
        return false;
    }
}
