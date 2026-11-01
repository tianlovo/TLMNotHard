package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNativePathRegion;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathOwnership;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidWalkEvaluator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 现代Follow/Move/Look的目标环境边界，读取真实路径事实，不使用旧终点或watchdog近似 */
public final class ModernFollowCoreActor
    implements FollowOwnerActor, FollowMoveActor, FollowCoreLookDriver.Actor, MaidPathNavigator.BrainTarget {

    private final EntityMaid maid;
    private final World world;
    private final MaidPathNavigator navigation;
    private MaidPolicyRuntime runtime;
    private Token token;
    private long worldTick;
    private int entityTick;
    private String ownerId;
    private MaidTask task;
    private OwnerWalk walk;
    private OwnerView look;
    private ModernPath pathMemory, navigationView;
    private ModernPath lastCreated;
    private PathEntity ownedPath;
    private Long cantReachSince;
    private boolean followStop;
    private boolean frameActive;
    private int teleportAttempts;

    public ModernFollowCoreActor(EntityMaid maid) {
        this.maid = Objects.requireNonNull(maid, "maid");
        world = Objects.requireNonNull(maid.worldObj, "world");
        if (!(maid.getNavigator() instanceof MaidPathNavigator modern))
            throw new IllegalArgumentException("Modern maid navigation required");
        navigation = modern;
    }

    public void beginFrame(MaidPolicyRuntime runtime, Token token) {
        this.runtime = runtime;
        this.token = token;
        worldTick = world.getTotalWorldTime();
        entityTick = maid.ticksExisted;
        ownerId = maid.func_152113_b();
        task = maid.getTask();
        frameActive = true;
        followStop = false;
        guard();
        if (!navigation.claimBrainTarget(this, token, this))
            throw new IllegalStateException("Foreign WALK_TARGET driver");
        navigationLease();
    }

    @Override
    public boolean isCurrent() {
        return frameActive && runtime != null
            && token != null
            && !world.isRemote
            && maid.worldObj == world
            && maid.isEntityAlive()
            && !maid.isDead
            && !MaidBrainLifecycle.isWorldUnloading(world)
            && maid.getBrainCoordinator()
                .peekPolicyRuntime() == runtime
            && runtime.requiresAfterNativeNavigation()
            && runtime.isReady()
            && runtime.getContext()
                .getMaidId()
                .equals(maid.getUniqueID())
            && runtime.getContext()
                .isCurrent(token)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && world.getTotalWorldTime() == worldTick
            && maid.ticksExisted == entityTick
            && Objects.equals(ownerId, maid.func_152113_b())
            && task == maid.getTask();
    }

    private void guard() {
        if (!isCurrent() || maid.getNavigator() != navigation)
            throw new IllegalStateException("Expired modern core actor frame");
    }

    private void navigationLease() {
        guard();
        var actual = navigation.getPath();
        if (actual == ownedPath) return;
        if (MaidPathOwnership.owns(actual, ownedPath)) {
            ownedPath = actual;
            navigationView = new ModernPath((MaidModernPath) actual, null);
            return;
        }
        var stop = navigation.lastStop();
        if (actual == null && ownedPath != null
            && (ownedPath.isFinished() || stop != null && MaidPathOwnership.owns(stop.path, ownedPath))) {
            navigationView = null;
            return;
        }
        throw new IllegalStateException(
            actual == null ? "Unexplained modern path disappearance" : "Foreign modern path");
    }

    @Override
    public Point currentPosition() {
        guard();
        if (walk == null) return null;
        var point = walk.currentBlockPosition();
        return new Point(point.x, point.y, point.z);
    }

    private EntityPlayerMP resolveOwner() {
        String id = maid.func_152113_b();
        if (id == null || id.isEmpty()) return null;
        final UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        var server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return null;
        EntityPlayerMP found = null;
        for (Object value : server.getConfigurationManager().playerEntityList) {
            if (value instanceof EntityPlayerMP player && uuid.equals(player.getUniqueID())) {
                if (found != null) throw new IllegalStateException("Duplicate online owner UUID");
                found = player;
            }
        }
        return found;
    }

    public Owner getOwner() {
        guard();
        var player = resolveOwner();
        return player == null ? null : new OwnerView(player);
    }

    public Object getWorldIdentity() {
        guard();
        return world;
    }

    public float getRestrictRadius() {
        guard();
        return maid.getHomeArea()
            .radius();
    }

    public boolean isGoingToBreath() {
        guard();
        return maid.getAquaticController()
            .getManager()
            .isGoingToBreath();
    }

    public boolean isHomeModeEnable() {
        guard();
        return maid.getHomeArea()
            .enabled();
    }

    public boolean canBrainMoving() {
        guard();
        return !maid.isSitting() && maid.ridingEntity == null && !maid.hasPose(MaidPose.SLEEPING) && !maid.getLeashed();
    }

    public boolean closerThan(Owner owner, double distance) {
        return maid.getDistanceSqToEntity(owner(owner).player) < distance * distance;
    }

    public boolean ownerIsWalkTarget(Owner owner) {
        return walk != null && walk.owner.player == owner(owner).player;
    }

    public void setWalkAndLookTargetMemories(Owner owner, float speedModifier, int stopDistance) {
        look = owner(owner);
        walk = new OwnerWalk(look, speedModifier, stopDistance);
    }

    public void resetNavigation() {
        stopNavigation();
        navigation.resetNavigation();
    }

    public void setGoingToBreath(boolean value) {
        guard();
        maid.getAquaticController()
            .getManager()
            .setGoingToBreath(value);
    }

    public void eraseTargetPos() {
        guard();
        /* 此组件不建立工作TARGET_POS，完整工作记忆由后续Brain接入 */ }

    public void stopFollowingTask() {
        guard();
        followStop = true;
    }

    public boolean consumeFollowStop() {
        boolean result = followStop;
        followStop = false;
        return result;
    }

    public boolean isWalkTargetRegistered() {
        guard();
        return true;
    }

    public boolean isPathRegistered() {
        guard();
        return true;
    }

    public boolean isCantReachSinceRegistered() {
        guard();
        return true;
    }

    public WalkTarget getWalkTarget() {
        guard();
        return walk;
    }

    public Path getPathMemory() {
        guard();
        return pathMemory;
    }

    public Long getCantReachSince() {
        guard();
        return cantReachSince;
    }

    public void eraseWalkTarget() {
        guard();
        walk = null;
    }

    public void eraseCantReachSince() {
        guard();
        cantReachSince = null;
    }

    public void setCantReachSince(long value) {
        guard();
        cantReachSince = value;
    }

    public void setPathMemory(Path value) {
        guard();
        pathMemory = value == null ? null : path(value);
    }

    public BlockPos blockPosition() {
        guard();
        return new BlockPos(
            MathHelper.floor_double(maid.posX),
            MathHelper.floor_double(maid.posY),
            MathHelper.floor_double(maid.posZ));
    }

    public long worldGameTime() {
        guard();
        return world.getTotalWorldTime();
    }

    public int nextWorldInt(int bound) {
        guard();
        return world.rand.nextInt(bound);
    }

    public Path createPath(BlockPos target, int accuracy) {
        navigationLease();
        var created = navigation.createBrainPath(this, target.x, target.y, target.z, accuracy);
        guard();
        lastCreated = created == null ? null : new ModernPath(created.path, created);
        return lastCreated;
    }

    public Path createPath(Vec3 target, int accuracy) {
        return createPath(
            new BlockPos(
                MathHelper.floor_double(target.x),
                MathHelper.floor_double(target.y),
                MathHelper.floor_double(target.z)),
            accuracy);
    }

    public Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle) {
        navigationLease();
        return FollowFallbackSampler.getPosTowards(new WorldFallback(), horizontalRange, verticalRange, target, angle);
    }

    public Path navigationPath() {
        navigationLease();
        return navigationView;
    }

    public boolean navigationIsDone() {
        navigationLease();
        return navigation.noPath();
    }

    public boolean navigationIsStuck() {
        navigationLease();
        return navigation.isModernStuck();
    }

    public boolean moveTo(Path value, double speed) {
        navigationLease();
        var requested = path(value);
        if (requested.created == null) throw new IllegalStateException("Path was not created for this installation");
        boolean result = navigation.installBrainPath(this, requested.created, speed);
        var actual = navigation.getPath();
        if (actual == requested.raw || actual == ownedPath) {
            ownedPath = actual;
            navigationView = actual == null ? null : new ModernPath((MaidModernPath) actual, null);
        }
        navigationLease();
        return result;
    }

    public void stopNavigation() {
        navigationLease();
        if (MaidPathOwnership.owns(navigation.getPath(), ownedPath)) navigation.clearPathEntity();
        ownedPath = null;
        navigationView = null;
    }

    /** 生命周期先使token失效，清理仍只撤销自身路线/目标，不覆盖后来接管者 */
    public void cancelOwnedResources() {
        try {
            if (MaidPathOwnership.owns(navigation.getPath(), ownedPath)) navigation.clearPathEntity();
        } finally {
            frameActive = false;
            navigation.releaseBrainTarget(this);
            navigation.resetNavigationIfIdle();
            ownedPath = null;
            navigationView = null;
            pathMemory = null;
            lastCreated = null;
            walk = null;
            look = null;
            cantReachSince = null;
            followStop = false;
        }
    }

    public boolean teleportToOwner(Owner value) {
        var player = owner(value).player;
        navigationLease();
        teleportAttempts = 0;
        var region = new MaidNativePathRegion(world);
        var evaluator = new MaidWalkEvaluator(navigation.context(region));
        int ownerX = MathHelper.floor_double(player.posX), ownerY = MathHelper.floor_double(player.posY),
            ownerZ = MathHelper.floor_double(player.posZ);
        for (int i = 0; i < 10; i++) {
            int x = ownerX + maid.getRNG()
                .nextInt(7) - 3;
            int y = ownerY + maid.getRNG()
                .nextInt(3) - 1;
            int z = ownerZ + maid.getRNG()
                .nextInt(7) - 3;
            teleportAttempts++;
            guard();
            if (Math.abs(x - player.posX) < 2 && Math.abs(z - player.posZ) < 2) continue;
            if (!world.blockExists(x, y - 1, z)
                || !world.blockExists(x, y + MathHelper.ceiling_float_int(maid.height), z)
                || world.getBlock(x, y - 1, z) == MaidLifeContent.snackCabinet) continue;
            var kind = evaluator.cellType(x, y, z);
            if (kind != MaidPathType.WALKABLE && kind != MaidPathType.WATER) continue;
            var translated = maid.boundingBox.getOffsetBoundingBox(
                x - MathHelper.floor_double(maid.posX),
                y - MathHelper.floor_double(maid.posY),
                z - MathHelper.floor_double(maid.posZ));
            if (!world.getCollidingBoundingBoxes(maid, translated)
                .isEmpty()) continue;
            navigationLease();
            maid.setLocationAndAngles(x + .5, y, z + .5, maid.rotationYaw, maid.rotationPitch);
            stopNavigation();
            walk = null;
            look = null;
            pathMemory = null;
            return true;
        }
        return false;
    }

    public boolean isLookTargetRegistered() {
        guard();
        return true;
    }

    public boolean hasLookTarget() {
        guard();
        return look != null;
    }

    public boolean isCurrentLookTargetVisible() {
        guard();
        return look != null && look.player.worldObj == world
            && look.player.isEntityAlive()
            && maid.getEntitySenses()
                .canSee(look.player);
    }

    public void lookAtCurrentTargetIfPresent() {
        guard();
        if (look != null) maid.getLookHelper()
            .setLookPosition(
                look.player.posX,
                look.player.posY + look.player.getEyeHeight(),
                look.player.posZ,
                10,
                maid.getVerticalFaceSpeed());
    }

    public void eraseLookTarget() {
        guard();
        look = null;
    }

    public WalkTarget peekWalkTarget() {
        return walk;
    }

    public Path peekPathMemory() {
        return pathMemory;
    }

    public Long peekCantReachSince() {
        return cantReachSince;
    }

    public PathEntity peekOwnedPath() {
        return ownedPath;
    }

    public Path peekLastCreated() {
        return lastCreated;
    }

    public int teleportAttempts() {
        return teleportAttempts;
    }

    private OwnerView owner(Owner value) {
        guard();
        if (!(value instanceof OwnerView view) || view.actor() != this)
            throw new IllegalArgumentException("Foreign owner view");
        return view;
    }

    private ModernPath path(Path value) {
        guard();
        if (!(value instanceof ModernPath view) || view.actor() != this || view.lease != token)
            throw new IllegalArgumentException("Foreign or expired modern path view");
        return view;
    }

    private final class OwnerView implements Owner {

        final EntityPlayerMP player;

        OwnerView(EntityPlayerMP player) {
            this.player = player;
        }

        ModernFollowCoreActor actor() {
            return ModernFollowCoreActor.this;
        }

        public Object getWorldIdentity() {
            guard();
            return player.worldObj;
        }

        public boolean isSpectator() {
            guard();
            return player.theItemInWorldManager.getGameType()
                .getID() == 3;
        }

        public boolean isDeadOrDying() {
            guard();
            return player.isDead || !player.isEntityAlive();
        }

        public boolean isUnderWater() {
            guard();
            return player.isInsideOfMaterial(Material.water);
        }
    }

    private final class OwnerWalk implements WalkTarget {

        final OwnerView owner;
        final float speed;
        final int closeEnough;

        OwnerWalk(OwnerView owner, float speed, int closeEnough) {
            this.owner = owner;
            this.speed = speed;
            this.closeEnough = closeEnough;
        }

        public BlockPos currentBlockPosition() {
            guard();
            return new BlockPos(
                MathHelper.floor_double(owner.player.posX),
                MathHelper.floor_double(owner.player.posY),
                MathHelper.floor_double(owner.player.posZ));
        }

        public float getSpeedModifier() {
            guard();
            return speed;
        }

        public int getCloseEnoughDistance() {
            guard();
            return closeEnough;
        }

        public boolean isEntityTrackerSpectator() {
            return owner.isSpectator();
        }
    }

    private final class ModernPath implements Path {

        final MaidModernPath raw;
        final MaidPathNavigator.BrainPath created;
        final Token lease = token;

        ModernPath(MaidModernPath raw, MaidPathNavigator.BrainPath created) {
            this.raw = raw;
            this.created = created;
        }

        ModernFollowCoreActor actor() {
            return ModernFollowCoreActor.this;
        }

        public Object getIdentity() {
            return raw;
        }

        public boolean canReach() {
            path(this);
            return raw.canReach();
        }
    }

    private final class WorldFallback implements FollowFallbackSampler.Actor {

        public Vec3 position() {
            guard();
            return new Vec3(maid.posX, maid.posY, maid.posZ);
        }

        public float nextEntityFloat() {
            guard();
            return maid.getRNG()
                .nextFloat();
        }

        public double nextEntityDouble() {
            guard();
            return maid.getRNG()
                .nextDouble();
        }

        public int nextEntityInt(int bound) {
            guard();
            return maid.getRNG()
                .nextInt(bound);
        }

        public boolean hasRestriction() {
            guard();
            return maid.getHomeArea()
                .enabled();
        }

        public BlockPos restrictionCenter() {
            guard();
            var center = maid.getHomeArea()
                .center();
            return new BlockPos(center.x, center.y, center.z);
        }

        public float restrictionRadius() {
            return getRestrictRadius();
        }

        public boolean isWithinRestriction(BlockPos point) {
            guard();
            return maid.getHomeArea()
                .contains(point.x, point.y, point.z);
        }

        public int minBuildHeight() {
            guard();
            return 0;
        }

        public int maxBuildHeight() {
            guard();
            return world.getHeight();
        }

        public boolean isStableDestination(BlockPos point) {
            guard();
            return navigation.isStableDestination(point.x, point.y, point.z);
        }

        public float pathfindingMalus(BlockPos point) {
            guard();
            return navigation.pathfindingCost(
                navigation.evaluator()
                    .cellType(point.x, point.y, point.z));
        }

        public float walkTargetValue(BlockPos point) {
            guard();
            if (world.getBlock(point.x, point.y - 1, point.z) == Blocks.grass) return 10;
            // 将目标维度的最低亮度映射为现代ambient输入，不把整张旧亮度表当作现代公式
            return FollowFallbackSampler.lightPathfindingCost(
                world.getBlockLightValue(point.x, point.y, point.z),
                world.provider.lightBrightnessTable[0]);
        }
    }
}
