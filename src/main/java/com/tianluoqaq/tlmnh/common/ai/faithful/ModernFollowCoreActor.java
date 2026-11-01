package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.potion.Potion;
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
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 现代Follow/Move/Look的目标环境边界，读取真实路径事实，不使用旧终点或watchdog近似 */
public final class ModernFollowCoreActor implements FollowOwnerActor, FollowMoveActor, FollowCoreLookDriver.Actor,
    MaidPathNavigator.BrainTarget, MaidAirActor, MaidClimbActor {

    private final EntityMaid maid;
    private final World world;
    private final MaidPathNavigator navigation;
    private MaidModernPerception perception;
    private MaidPolicyRuntime runtime;
    private Token token;
    private long worldTick;
    private int entityTick;
    private String ownerId;
    private MaidTask task;
    private WalkTarget walk;
    private Object look;
    private MaidItemUseState.Lease breathItemLease;
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

    void bindPerception(MaidModernPerception memory) {
        if (perception != null) throw new IllegalStateException("Perception already bound");
        perception = Objects.requireNonNull(memory, "memory");
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
        return walk instanceof OwnerWalk target && target.owner.player == owner(owner).player;
    }

    public void setWalkAndLookTargetMemories(Owner owner, float speedModifier, int stopDistance) {
        var view = owner(owner);
        look = view;
        walk = new OwnerWalk(view, speedModifier, stopDistance);
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
        boolean ownsTarget = navigation.isBrainTargetOwner(this);
        try {
            if (MaidPathOwnership.owns(navigation.getPath(), ownedPath)) navigation.clearPathEntity();
            if (breathItemLease != null && maid.getItemUseState()
                .peek() == breathItemLease) maid.getMealController()
                    .cancel();
        } finally {
            breathItemLease = null;
            if (ownsTarget) {
                maid.setCanClimb(false);
                maid.setSneaking(false);
                var swim = maid.getAquaticController()
                    .getManager();
                swim.setGoingToBreath(false);
                swim.setEatBreatheItem(false);
            }
            frameActive = false;
            navigation.releaseBrainTarget(this);
            if (ownsTarget) navigation.resetNavigationIfIdle();
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
        if (look instanceof Point || look instanceof Vec3) return true;
        Entity entity = look instanceof OwnerView target ? target.player
            : look instanceof EntityLook target ? target.entity : null;
        if (entity == null) return false;
        // 原EntityTracker对非活体始终true，不以拾取移除/实时ray提前截断LOOK期限
        if (!(entity instanceof EntityLivingBase living)) return true;
        return living.isEntityAlive() && perception.visible() != null
            && perception.visible()
                .contains(living);
    }

    public void lookAtCurrentTargetIfPresent() {
        guard();
        if (look instanceof OwnerView target) maid.getLookHelper()
            .setLookPosition(
                target.player.posX,
                target.player.posY + target.player.getEyeHeight(),
                target.player.posZ,
                10,
                maid.getVerticalFaceSpeed());
        else if (look instanceof Point target) maid.getLookHelper()
            .setLookPosition(target.x + .5, target.y + .5, target.z + .5, 10, maid.getVerticalFaceSpeed());
        else if (look instanceof Vec3 target) maid.getLookHelper()
            .setLookPosition(target.x, target.y, target.z, 10, maid.getVerticalFaceSpeed());
        else if (look instanceof EntityLook target) maid.getLookHelper()
            .setLookPosition(
                target.entity.posX,
                target.entity.posY + target.entity.getEyeHeight(),
                target.entity.posZ,
                10,
                maid.getVerticalFaceSpeed());
    }

    public com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS openPickupSearch() {
        navigationLease();
        var area = maid.getHomeArea();
        var center = area.enabled() ? new Point(area.center().x, area.center().y, area.center().z)
            : airSearchPosition();
        return navigation.createArrivalMap(center, perception.taskSearchRadius(), 7);
    }

    public void setEntityWalkAndLook(Entity entity, float speed, int closeDistance) {
        guard();
        look = new EntityLook(entity);
        walk = new EntityWalk(entity, speed, closeDistance);
    }

    public boolean isEatBreatheItem() {
        guard();
        return maid.getAquaticController()
            .getManager()
            .isEatBreatheItem();
    }

    public Point walkTargetPosition() {
        return currentPosition();
    }

    public int distanceManhattan(Point pos) {
        return blockPosition().manhattanDistance(new BlockPos(pos.x, pos.y, pos.z));
    }

    public int airSupply() {
        guard();
        return maid.getAir();
    }

    public boolean hasBreathingEffect() {
        guard();
        // 目标已核水下呼吸；当前移植未提供现代CONDUIT_POWER，后续不能据此称完整效果映射
        return maid.isPotionActive(Potion.waterBreathing);
    }

    public boolean hasDrownBauble() {
        guard();
        // 当前库存尚无原溺水饰品槽/物品，保留任务分支，待装备扩展后接实际物品事实
        return false;
    }

    public boolean isUsingItem() {
        guard();
        return maid.getItemUseState()
            .peek() != null;
    }

    public boolean tryEatBreatheItem() {
        guard();
        if (!maid.getMealController()
            .tryStartBreathing()) return false;
        breathItemLease = maid.getItemUseState()
            .peek();
        maid.getAquaticController()
            .getManager()
            .setEatBreatheItem(true);
        return true;
    }

    public Point airSearchPosition() {
        var pos = blockPosition();
        return new Point(pos.x, pos.y, pos.z);
    }

    public int seaLevel() {
        guard();
        return new MaidNativePathRegion(world).seaLevel();
    }

    public Optional<Point> findAirWithBfs(int range) {
        navigationLease();
        var home = maid.getHomeArea();
        var center = home.enabled() ? new Point(home.center().x, home.center().y, home.center().z)
            : airSearchPosition();
        try (var map = navigation.createArrivalMap(center, range, range)) {
            return map.find(this::givesAir);
        }
    }

    public boolean canPathReach(Point pos) {
        navigationLease();
        var created = navigation.createBrainPath(this, pos.x, pos.y, pos.z, 0);
        return created != null && created.path.canReach();
    }

    public boolean givesAir(Point pos) {
        guard();
        if (!world.blockExists(pos.x, pos.y, pos.z)) return false;
        var block = world.getBlock(pos.x, pos.y, pos.z);
        boolean bubble = block.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.BlockBubbleColumn");
        if (block.getMaterial()
            .isLiquid() && !bubble) return false;
        var box = block.getCollisionBoundingBoxFromPool(world, pos.x, pos.y, pos.z);
        return box == null || box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ;
    }

    public void setAirWalkAndLook(Point pos, float speed, int closeDistance) {
        setPositionWalkAndLook(pos, speed, closeDistance);
    }

    public void setPositionWalkAndLook(Point pos, float speed, int closeDistance) {
        guard();
        look = pos;
        walk = new PositionWalk(pos, speed, closeDistance);
    }

    public boolean isWithinHome(BlockPos pos) {
        guard();
        return maid.getHomeArea()
            .contains(pos.x, pos.y, pos.z);
    }

    public Vec3 entityPosition() {
        guard();
        return new Vec3(maid.posX, maid.posY, maid.posZ);
    }

    public Vec3 dangerPosition() {
        guard();
        var entity = perception.nearestHostile();
        return entity == null ? null : new Vec3(entity.posX, entity.posY, entity.posZ);
    }

    public Vec3 findLandAway(int radius, int vertical, Vec3 avoid) {
        guard();
        return MaidLandRandomSampler.getPosAway(new WorldFallback(), radius, vertical, avoid);
    }

    public void setVectorWalkTarget(Vec3 pos, float speed, int closeDistance) {
        guard();
        walk = new PositionWalk(
            new Point(MathHelper.floor_double(pos.x), MathHelper.floor_double(pos.y), MathHelper.floor_double(pos.z)),
            speed,
            closeDistance);
    }

    public void leaveMountOrSitForDanger() {
        guard();
        var mount = maid.ridingEntity;
        if (mount != null && mount.riddenByEntity instanceof EntityPlayer) return;
        if (maid.isSitting()) maid.setSitting(false);
        if (maid.ridingEntity != null) maid.mountEntity(null);
    }

    public void clearSleepingIfPresent() {
        guard();
        if (!maid.hasPose(MaidPose.SLEEPING)) return;
        var life = maid.getBrainCoordinator()
            .getLifeController();
        if (life.isSleeping()) life.cancel();
        guard();
        maid.setPose(MaidPose.SLEEPING, false);
    }

    public boolean activeClimbing() {
        guard();
        return maid.getConfigManager()
            .isActiveClimbing();
    }

    public boolean onClimbable() {
        navigationLease();
        return maid.isOnLadder();
    }

    public Point climbFeet() {
        return airSearchPosition();
    }

    public boolean feetAreLadder() {
        guard();
        var pos = climbFeet();
        return world.blockExists(pos.x, pos.y, pos.z) && world.getBlock(pos.x, pos.y, pos.z)
            .isLadder(world, pos.x, pos.y, pos.z, maid);
    }

    public void centerOnBlock(Point pos) {
        navigationLease();
        maid.setLocationAndAngles(pos.x + .5, pos.y, pos.z + .5, maid.rotationYaw, maid.rotationPitch);
    }

    public void setCanClimb(boolean value) {
        guard();
        maid.setCanClimb(value);
    }

    public void setShiftDown(boolean value) {
        guard();
        maid.setSneaking(value);
    }

    public void setClimbMotion(double x, double y, double z) {
        navigationLease();
        maid.motionX = x;
        maid.motionY = y;
        maid.motionZ = z;
    }

    public double climbMotionY() {
        guard();
        return maid.motionY;
    }

    public void lookAtClimbNode(Point pos) {
        guard();
        look = new Vec3(pos.x, pos.y, pos.z);
    }

    public ClimbPath climbPath() {
        navigationLease();
        var actual = navigation.getPath();
        return actual == null ? null : new ClimbView(actual);
    }

    private final class ClimbView implements ClimbPath {

        final PathEntity raw;
        final Token lease = token;
        final long createdWorldTick = worldTick;
        final int createdEntityTick = entityTick;
        final Point[] nodes;

        ClimbView(PathEntity raw) {
            this.raw = raw;
            nodes = new Point[raw.getCurrentPathLength()];
            for (int i = 0; i < nodes.length; i++) {
                var node = raw.getPathPointFromIndex(i);
                nodes[i] = new Point(node.xCoord, node.yCoord, node.zCoord);
            }
        }

        void check() {
            navigationLease();
            if (token != lease || worldTick != createdWorldTick
                || entityTick != createdEntityTick
                || navigation.getPath() != raw) throw new IllegalStateException("Expired climbing path view");
        }

        public boolean done() {
            check();
            return raw.isFinished();
        }

        public int index() {
            check();
            return raw.getCurrentPathIndex();
        }

        public int length() {
            check();
            return raw.getCurrentPathLength();
        }

        public Point node(int index) {
            check();
            return nodes[index];
        }

        public Point end() {
            return node(length() - 1);
        }

        public void index(int value) {
            check();
            raw.setCurrentPathIndex(value);
        }
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

    private final class PositionWalk implements WalkTarget {

        final Point pos;
        final float speed;
        final int closeEnough;

        PositionWalk(Point pos, float speed, int closeEnough) {
            this.pos = pos;
            this.speed = speed;
            this.closeEnough = closeEnough;
        }

        public BlockPos currentBlockPosition() {
            guard();
            return new BlockPos(pos.x, pos.y, pos.z);
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
            guard();
            return false;
        }
    }

    private static final class EntityLook {

        final Entity entity;

        EntityLook(Entity entity) {
            this.entity = entity;
        }
    }

    private final class EntityWalk implements WalkTarget {

        final Entity entity;
        final float speed;
        final int closeEnough;

        EntityWalk(Entity entity, float speed, int closeEnough) {
            this.entity = entity;
            this.speed = speed;
            this.closeEnough = closeEnough;
        }

        public BlockPos currentBlockPosition() {
            guard();
            return new BlockPos(
                MathHelper.floor_double(entity.posX),
                MathHelper.floor_double(entity.posY),
                MathHelper.floor_double(entity.posZ));
        }

        public float getSpeedModifier() {
            guard();
            return speed;
        }

        public Vec3 currentPosition() {
            guard();
            return new Vec3(entity.posX, entity.posY, entity.posZ);
        }

        public int getCloseEnoughDistance() {
            guard();
            return closeEnough;
        }

        public boolean isEntityTrackerSpectator() {
            guard();
            return entity instanceof EntityPlayerMP player && player.theItemInWorldManager.getGameType()
                .getID() == 3;
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

        public Vec3 currentPosition() {
            guard();
            return new Vec3(owner.player.posX, owner.player.posY, owner.player.posZ);
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

    private final class WorldFallback implements MaidLandRandomSampler.Actor {

        public boolean solid(BlockPos pos) {
            guard();
            if (!world.blockExists(pos.x, pos.y, pos.z)) return false;
            var block = world.getBlock(pos.x, pos.y, pos.z);
            block.setBlockBoundsBasedOnState(world, pos.x, pos.y, pos.z);
            var box = block.getCollisionBoundingBoxFromPool(world, pos.x, pos.y, pos.z);
            if (box == null) return false;
            double x = box.maxX - box.minX, y = box.maxY - box.minY, z = box.maxZ - box.minZ;
            return (x + y + z) / 3 >= .7291666666666666D || y >= 1;
        }

        public boolean water(BlockPos pos) {
            guard();
            return world.blockExists(pos.x, pos.y, pos.z) && world.getBlock(pos.x, pos.y, pos.z)
                .getMaterial() == Material.water;
        }

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
