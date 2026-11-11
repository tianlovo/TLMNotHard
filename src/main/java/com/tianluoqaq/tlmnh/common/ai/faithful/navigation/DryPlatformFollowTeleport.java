package com.tianluoqaq.tlmnh.common.ai.faithful.navigation;

import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/**
 * 原版 EntityMaid.teleportToOwner 的 native 干燥石头平台适配
 * 候选顺序来自固定上游 8a3ac5e9；这里只接受明确的开发合同，不包含完整现代节点分类
 */
public final class DryPlatformFollowTeleport {

    private DryPlatformFollowTeleport() {}

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
        Objects.requireNonNull(maid, FaithfulNavigationIds.ID_MAID);
        Objects.requireNonNull(owner, FaithfulNavigationIds.ID_OWNER);
        Objects.requireNonNull(rng, FaithfulNavigationIds.ID_RNG);
        Objects.requireNonNull(access, FaithfulNavigationIds.ID_ACCESS);
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
        for (int attempt = 0; attempt < FaithfulNavigationTuning.MAX_ATTEMPTS; attempt++) {
            denied = denied(access, progress);
            if (denied != null) return denied;
            progress.attempts++;
            // 原版在水平距离过滤之前依次取完 x/y/z，不跳过任何一次随机调用
            int x = ownerX + nextInt(rng, FaithfulNavigationTuning.DRY_PLATFORM_NEXT_INT_SECOND_PARAM, progress)
                - FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_X_OFFSET;
            int y = ownerY + nextInt(rng, FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_X_OFFSET, progress) - 1;
            int z = ownerZ + nextInt(rng, FaithfulNavigationTuning.DRY_PLATFORM_NEXT_INT_SECOND_PARAM, progress)
                - FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_X_OFFSET;
            progress.hasCandidate = true;
            progress.x = x;
            progress.y = y;
            progress.z = z;
            denied = denied(access, progress);
            if (denied != null) return denied;
            if (Math.abs(x - owner.posX) < FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_PARAM
                && Math.abs(z - owner.posZ) < FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_PARAM) {
                progress.lastRejection = FaithfulNavigationIds.ID_CANDIDATE_TOO_CLOSE;
                continue;
            }

            // 石头与空气均不属于固定上游的避让 tag；未知块已在任何 RNG 调用前拒绝
            Block feet = maid.worldObj.getBlock(x, y, z);
            Block below = maid.worldObj.getBlock(x, y - 1, z);
            if (feet != Blocks.air || below != Blocks.stone) {
                progress.lastRejection = FaithfulNavigationIds.ID_KNOWN_NODE_NOT_WALKABLE;
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
                progress.lastRejection = FaithfulNavigationIds.ID_NATIVE_COLLISION;
                continue;
            }
            denied = denied(access, progress);
            if (denied != null) return denied;
            maid.setLocationAndAngles(
                x + FaithfulNavigationTuning.CENTER_OFFSET_D,
                y,
                z + FaithfulNavigationTuning.CENTER_OFFSET_D,
                maid.rotationYaw,
                maid.rotationPitch);
            progress.moved = true;
            access.stopOwnedNavigation();
            denied = denied(access, progress);
            if (denied != null) return denied;
            access.clearSuccessfulTeleportMemories();
            denied = denied(access, progress);
            if (denied != null) return denied;
            return result(Status.SUCCESS, progress, FaithfulNavigationIds.ID_STONE_AIR_NATIVE_COLLISION_PASSED);
        }
        return result(Status.EXHAUSTED, progress, progress.lastRejection);
    }

    private static int nextInt(RandomInts rng, int bound, Progress progress) {
        progress.rngCalls++;
        int value = rng.nextInt(bound);
        if (value < 0 || value >= bound) throw new IllegalArgumentException(
            FaithfulNavigationDiagnostics.MSG_RANDOMINTS_VALUE_OUTSIDE_BOUND + bound);
        return value;
    }

    private static Result denied(Access access, Progress progress) {
        if (!access.isCurrent())
            return result(Status.STALE, progress, FaithfulNavigationIds.ID_POLICY_TOKEN_NOT_CURRENT);
        if (!access.canOwnNavigation())
            return result(Status.OWNERSHIP_LOST, progress, FaithfulNavigationIds.ID_NAVIGATION_NOT_OWNED);
        return null;
    }

    private static Result result(Status status, Progress progress, String diagnostic) {
        return new Result(status, progress, diagnostic);
    }

    private static String checkDomain(EntityMaid maid, EntityLivingBase owner) {
        if (maid.worldObj == null || maid.worldObj.isRemote || maid.worldObj != owner.worldObj)
            return FaithfulNavigationIds.ID_SERVER_SAME_WORLD_REQUIRED;
        if (!maid.isEntityAlive() || !owner.isEntityAlive() || maid.isDead || owner.isDead)
            return FaithfulNavigationIds.ID_LIVING_PARTICIPANTS_REQUIRED;
        if (maid.ridingEntity != null || maid.getLeashed() || maid.isSitting() || maid.hasPose(MaidPose.SLEEPING))
            return FaithfulNavigationIds.ID_MAID_MOVEMENT_NOT_PERMITTED;
        if (!halfGrid(maid.posX) || !halfGrid(maid.posZ)
            || !halfGrid(owner.posX)
            || !halfGrid(owner.posZ)
            || !integerY(maid.posY)
            || !integerY(owner.posY)) return FaithfulNavigationIds.ID_HALF_GRID_INTEGER_Y_REQUIRED;
        if (maid.width != FaithfulNavigationTuning.TARGET_WIDTH_THRESHOLD
            || maid.height != FaithfulNavigationTuning.TARGET_HEIGHT_THRESHOLD
            || maid.yOffset != 0
            || maid.ySize != 0
            || maid.boundingBox.minY != maid.posY) return FaithfulNavigationIds.ID_ORIGINAL_MAID_BBOX_REQUIRED;
        int x = MathHelper.floor_double(owner.posX), y = MathHelper.floor_double(owner.posY);
        int z = MathHelper.floor_double(owner.posZ);
        if (y < FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_PARAM || y > FaithfulNavigationTuning.MAX_Y_THRESHOLD
            || maid.posY < FaithfulNavigationTuning.MIN_POS_Y_THRESHOLD
            || maid.posY > FaithfulNavigationTuning.MAX_POS_Y_THRESHOLD)
            return FaithfulNavigationIds.ID_BUILD_HEIGHT_OUTSIDE_CONTRACT;
        var chunks = maid.worldObj.getChunkProvider();
        for (int chunkX = (x - FaithfulNavigationTuning.DOMAIN_RADIUS)
            >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT; chunkX
                <= (x + FaithfulNavigationTuning.DOMAIN_RADIUS)
                    >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT; chunkX++) {
            for (int chunkZ = (z - FaithfulNavigationTuning.DOMAIN_RADIUS)
                >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT; chunkZ
                    <= (z + FaithfulNavigationTuning.DOMAIN_RADIUS)
                        >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT; chunkZ++) {
                if (!chunks.chunkExists(chunkX, chunkZ)) return FaithfulNavigationIds.ID_CANDIDATE_CHUNK_UNLOADED;
            }
        }
        for (int bx = x - FaithfulNavigationTuning.DOMAIN_RADIUS; bx
            <= x + FaithfulNavigationTuning.DOMAIN_RADIUS; bx++) {
            for (int bz = z - FaithfulNavigationTuning.DOMAIN_RADIUS; bz
                <= z + FaithfulNavigationTuning.DOMAIN_RADIUS; bz++) {
                for (int by = y - FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_PARAM; by
                    <= y + FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_X_OFFSET; by++) {
                    Block block = maid.worldObj.getBlock(bx, by, bz);
                    if (block != Blocks.air && block != Blocks.stone)
                        return FaithfulNavigationIds.ID_UNKNOWN_CANDIDATE_OR_NEIGHBOUR_BLOCK;
                    if (by == y - 1 && block != Blocks.stone)
                        return FaithfulNavigationIds.ID_COMPLETE_STONE_FLOOR_REQUIRED;
                }
            }
        }
        var area = AxisAlignedBB.getBoundingBox(
            x - FaithfulNavigationTuning.CHUNK_SECTION_SHIFT,
            y - FaithfulNavigationTuning.DRY_PLATFORM_TRY_TELEPORT_PARAM,
            z - FaithfulNavigationTuning.CHUNK_SECTION_SHIFT,
            x + FaithfulNavigationTuning.DRY_PLATFORM_AREA,
            y + FaithfulNavigationTuning.CHUNK_SECTION_SHIFT,
            z + FaithfulNavigationTuning.DRY_PLATFORM_AREA);
        for (Object entity : maid.worldObj.getEntitiesWithinAABBExcludingEntity(maid, area)) {
            if (entity != owner) return FaithfulNavigationIds.ID_OTHER_ENTITY_INSIDE_CANDIDATE_DOMAIN;
        }
        int maidX = MathHelper.floor_double(maid.posX), maidY = MathHelper.floor_double(maid.posY);
        int maidZ = MathHelper.floor_double(maid.posZ);
        if (!chunks.chunkExists(
            maidX >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT,
            maidZ >> FaithfulNavigationTuning.CHUNK_SECTION_SHIFT)) return FaithfulNavigationIds.ID_MAID_CHUNK_UNLOADED;
        if (maid.worldObj.getBlock(maidX, maidY - 1, maidZ) != Blocks.stone)
            return FaithfulNavigationIds.ID_MAID_STONE_FLOOR_REQUIRED;
        if (maid.worldObj.getBlock(maidX, maidY, maidZ) != Blocks.air
            || maid.worldObj.getBlock(maidX, maidY + 1, maidZ) != Blocks.air)
            return FaithfulNavigationIds.ID_MAID_DRY_AIR_REQUIRED;
        return null;
    }

    private static boolean halfGrid(double value) {
        return Double.isFinite(value) && Math.abs(value) < FaithfulNavigationTuning.WORLD_LIMIT
            && value == Math.floor(value) + FaithfulNavigationTuning.CENTER_OFFSET_D;
    }

    private static boolean integerY(double value) {
        return Double.isFinite(value) && value == Math.floor(value);
    }

    private static final class Progress {

        private int attempts, rngCalls, collisionQueries, collisionRejections, x, y, z;
        private boolean hasCandidate, moved;
        private String lastRejection = FaithfulNavigationIds.ID_NO_CANDIDATE_ACCEPTED;
    }
}
