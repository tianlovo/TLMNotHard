package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/**
 * 原版 EntityMaid.teleportToOwner 的 RC2 干燥石头平台适配
 * 候选顺序来自固定上游 8a3ac5e9；这里只接受明确的开发合同，不包含完整现代节点分类
 */
public final class Rc2FollowTeleport {

    private static final int MAX_ATTEMPTS = 10;
    private static final int DOMAIN_RADIUS = 4;
    private static final int WORLD_LIMIT = 29_999_990;

    private Rc2FollowTeleport() {}

    public interface RandomInts {

        int nextInt(int bound);
    }

    public interface Access {

        boolean isCurrent();

        /** 是否允许本动作修改导航；不能只据当前 path 为 null 来判断 */
        boolean canOwnNavigation();

        /** 只停止本动作持有的导航，不清目标记忆 */
        void stopOwnedNavigation();

        /** 只清本动作持有的 WALK/LOOK/ATTACK/PATH，调用方维护各自资源身份 */
        void clearSuccessfulTeleportMemories();
    }

    public enum Status {
        SUCCESS,
        EXHAUSTED,
        OUT_OF_SCOPE,
        STALE,
        OWNERSHIP_LOST
    }

    public static final class Result {

        public final Status status;
        public final int attempts, rngCalls, collisionQueries, collisionRejections;
        public final boolean hasCandidate, moved;
        public final int candidateX, candidateY, candidateZ;
        public final String diagnostic;

        private Result(Status status, Progress progress, String diagnostic) {
            this.status = status;
            attempts = progress.attempts;
            rngCalls = progress.rngCalls;
            collisionQueries = progress.collisionQueries;
            collisionRejections = progress.collisionRejections;
            hasCandidate = progress.hasCandidate;
            moved = progress.moved;
            candidateX = progress.x;
            candidateY = progress.y;
            candidateZ = progress.z;
            this.diagnostic = diagnostic;
        }

        public Status getStatus() {
            return status;
        }

        public int getAttempts() {
            return attempts;
        }

        public int getRngCalls() {
            return rngCalls;
        }
    }

    public static Result tryTeleport(EntityMaid maid, EntityLivingBase owner, RandomInts rng, Access access) {
        Objects.requireNonNull(maid, "maid");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(rng, "rng");
        Objects.requireNonNull(access, "access");
        var progress = new Progress();
        var denied = denied(access, progress);
        if (denied != null) return denied;
        String outside = checkDomain(maid, owner);
        if (outside != null) return result(Status.OUT_OF_SCOPE, progress, outside);
        denied = denied(access, progress);
        if (denied != null) return denied;

        int ownerX = MathHelper.floor_double(owner.posX);
        int ownerY = MathHelper.floor_double(owner.posY);
        int ownerZ = MathHelper.floor_double(owner.posZ);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            denied = denied(access, progress);
            if (denied != null) return denied;
            progress.attempts++;
            // 原版在水平距离过滤之前依次取完 x/y/z，不跳过任何一次随机调用
            int x = ownerX + nextInt(rng, 7, progress) - 3;
            int y = ownerY + nextInt(rng, 3, progress) - 1;
            int z = ownerZ + nextInt(rng, 7, progress) - 3;
            progress.hasCandidate = true;
            progress.x = x;
            progress.y = y;
            progress.z = z;
            denied = denied(access, progress);
            if (denied != null) return denied;
            if (Math.abs(x - owner.posX) < 2 && Math.abs(z - owner.posZ) < 2) {
                progress.lastRejection = "candidate_too_close";
                continue;
            }

            // 石头与空气均不属于固定上游的避让 tag；未知块已在任何 RNG 调用前拒绝
            Block feet = maid.worldObj.getBlock(x, y, z);
            Block below = maid.worldObj.getBlock(x, y - 1, z);
            if (feet != Blocks.air || below != Blocks.stone) {
                progress.lastRejection = "known_node_not_walkable";
                continue;
            }
            int dx = x - MathHelper.floor_double(maid.posX);
            int dy = y - MathHelper.floor_double(maid.posY);
            int dz = z - MathHelper.floor_double(maid.posZ);
            var translated = maid.boundingBox.getOffsetBoundingBox(dx, dy, dz);
            progress.collisionQueries++;
            if (!maid.worldObj.getCollidingBoundingBoxes(maid, translated)
                .isEmpty()) {
                progress.collisionRejections++;
                progress.lastRejection = "native_collision";
                continue;
            }
            denied = denied(access, progress);
            if (denied != null) return denied;
            maid.setLocationAndAngles(x + 0.5D, y, z + 0.5D, maid.rotationYaw, maid.rotationPitch);
            progress.moved = true;
            access.stopOwnedNavigation();
            denied = denied(access, progress);
            if (denied != null) return denied;
            access.clearSuccessfulTeleportMemories();
            denied = denied(access, progress);
            if (denied != null) return denied;
            return result(Status.SUCCESS, progress, "stone_air_native_collision_passed");
        }
        return result(Status.EXHAUSTED, progress, progress.lastRejection);
    }

    private static int nextInt(RandomInts rng, int bound, Progress progress) {
        progress.rngCalls++;
        int value = rng.nextInt(bound);
        if (value < 0 || value >= bound) throw new IllegalArgumentException("RandomInts value outside bound " + bound);
        return value;
    }

    private static Result denied(Access access, Progress progress) {
        if (!access.isCurrent()) return result(Status.STALE, progress, "policy_token_not_current");
        if (!access.canOwnNavigation()) return result(Status.OWNERSHIP_LOST, progress, "navigation_not_owned");
        return null;
    }

    private static Result result(Status status, Progress progress, String diagnostic) {
        return new Result(status, progress, diagnostic);
    }

    private static String checkDomain(EntityMaid maid, EntityLivingBase owner) {
        if (maid.worldObj == null || maid.worldObj.isRemote || maid.worldObj != owner.worldObj)
            return "server_same_world_required";
        if (!maid.isEntityAlive() || !owner.isEntityAlive() || maid.isDead || owner.isDead)
            return "living_participants_required";
        if (maid.ridingEntity != null || maid.getLeashed() || maid.isSitting() || maid.hasPose(MaidPose.SLEEPING))
            return "maid_movement_not_permitted";
        if (!halfGrid(maid.posX) || !halfGrid(maid.posZ)
            || !halfGrid(owner.posX)
            || !halfGrid(owner.posZ)
            || !integerY(maid.posY)
            || !integerY(owner.posY)) return "half_grid_integer_y_required";
        if (maid.width != 0.6F || maid.height != 1.5F
            || maid.yOffset != 0
            || maid.ySize != 0
            || maid.boundingBox.minY != maid.posY) return "original_maid_bbox_required";
        int x = MathHelper.floor_double(owner.posX), y = MathHelper.floor_double(owner.posY);
        int z = MathHelper.floor_double(owner.posZ);
        if (y < 2 || y > 252 || maid.posY < 1 || maid.posY > 253) return "build_height_outside_contract";
        var chunks = maid.worldObj.getChunkProvider();
        for (int chunkX = (x - DOMAIN_RADIUS) >> 4; chunkX <= (x + DOMAIN_RADIUS) >> 4; chunkX++) {
            for (int chunkZ = (z - DOMAIN_RADIUS) >> 4; chunkZ <= (z + DOMAIN_RADIUS) >> 4; chunkZ++) {
                if (!chunks.chunkExists(chunkX, chunkZ)) return "candidate_chunk_unloaded";
            }
        }
        for (int bx = x - DOMAIN_RADIUS; bx <= x + DOMAIN_RADIUS; bx++) {
            for (int bz = z - DOMAIN_RADIUS; bz <= z + DOMAIN_RADIUS; bz++) {
                for (int by = y - 2; by <= y + 3; by++) {
                    Block block = maid.worldObj.getBlock(bx, by, bz);
                    if (block != Blocks.air && block != Blocks.stone) return "unknown_candidate_or_neighbour_block";
                    if (by == y - 1 && block != Blocks.stone) return "complete_stone_floor_required";
                }
            }
        }
        var area = AxisAlignedBB.getBoundingBox(x - 4, y - 2, z - 4, x + 5, y + 4, z + 5);
        for (Object entity : maid.worldObj.getEntitiesWithinAABBExcludingEntity(maid, area)) {
            if (entity != owner) return "other_entity_inside_candidate_domain";
        }
        int maidX = MathHelper.floor_double(maid.posX), maidY = MathHelper.floor_double(maid.posY);
        int maidZ = MathHelper.floor_double(maid.posZ);
        if (!chunks.chunkExists(maidX >> 4, maidZ >> 4)) return "maid_chunk_unloaded";
        if (maid.worldObj.getBlock(maidX, maidY - 1, maidZ) != Blocks.stone) return "maid_stone_floor_required";
        if (maid.worldObj.getBlock(maidX, maidY, maidZ) != Blocks.air
            || maid.worldObj.getBlock(maidX, maidY + 1, maidZ) != Blocks.air) return "maid_dry_air_required";
        return null;
    }

    private static boolean halfGrid(double value) {
        return Double.isFinite(value) && Math.abs(value) < WORLD_LIMIT && value == Math.floor(value) + 0.5D;
    }

    private static boolean integerY(double value) {
        return Double.isFinite(value) && value == Math.floor(value);
    }

    private static final class Progress {

        private int attempts, rngCalls, collisionQueries, collisionRejections, x, y, z;
        private boolean hasCandidate, moved;
        private String lastRejection = "no_candidate_accepted";
    }
}
