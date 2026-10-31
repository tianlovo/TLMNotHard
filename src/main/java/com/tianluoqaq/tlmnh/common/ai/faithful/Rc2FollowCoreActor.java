package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.EndpointRelation;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

/** FOLLOW_CORE组件的RC2执行边界，仅接受显式开发干燥合同，不提供完整家园/呼吸状态 */
public final class Rc2FollowCoreActor implements FollowOwnerActor, FollowMoveActor, FollowCoreLookDriver.Actor {

    /** 必须核实已加载的完整搜索域、STONE平面/空气、无危险和未知块；不允许强加载 */
    public interface DryDomain {

        void verify(EntityMaid maid, BlockPos target);
    }

    public static final class Contract {

        private float radius;
        private boolean homeMode, goingToBreath;
        private final transient DryDomain domain;

        public Contract(float radius, boolean homeMode, boolean goingToBreath, DryDomain domain) {
            setRestrictRadius(radius);
            this.homeMode = homeMode;
            this.goingToBreath = goingToBreath;
            this.domain = Objects.requireNonNull(domain, "domain");
        }

        public void setRestrictRadius(float radius) {
            if (!Float.isFinite(radius) || radius < 3 || radius > 32)
                throw new IllegalArgumentException("Development radius outside original configuration range");
            this.radius = radius;
        }

        public void setHomeMode(boolean homeMode) {
            this.homeMode = homeMode;
        }

        public void setGoingToBreath(boolean goingToBreath) {
            this.goingToBreath = goingToBreath;
        }

        public float getRestrictRadius() {
            return radius;
        }

        public boolean isHomeMode() {
            return homeMode;
        }

        public boolean isGoingToBreath() {
            return goingToBreath;
        }
    }

    public static final class OutsideContractException extends IllegalStateException {

        public OutsideContractException(String reason) {
            super("FOLLOW_CORE_COMPONENT out of scope: " + reason);
        }
    }

    private final EntityMaid maid;
    private final World world;
    private final PathNavigate navigator;
    private final Contract contract;
    private final MaidBoundedPathPlanner planner = new MaidBoundedPathPlanner();
    private MaidPolicyRuntime runtime;
    private Token token;
    private long worldTick;
    private int entityTick;
    private String ownerId;
    private OwnerWalk walk;
    private OwnerView look;
    private NativePath pathMemory, navigationView;
    private PathEntity ownedPath;
    private Long cantReachSince;
    private boolean followStop;
    private MaidBoundedPathPlanner.Creation lastCreation;
    private MaidBoundedPathPlanner.Installation lastInstallation;
    private Rc2FollowTeleport.Result lastTeleport;

    public Rc2FollowCoreActor(EntityMaid maid, Contract contract) {
        this.maid = Objects.requireNonNull(maid, "maid");
        this.contract = Objects.requireNonNull(contract, "contract");
        world = maid.worldObj;
        navigator = maid.getNavigator();
    }

    void beginFrame(MaidPolicyRuntime runtime, Token token) {
        this.runtime = runtime;
        this.token = token;
        worldTick = world == null ? 0 : world.getTotalWorldTime();
        entityTick = maid.ticksExisted;
        ownerId = maid.func_152113_b();
        followStop = false;
        guard();
        navigationLease();
        verifyDry(blockPosition());
        if (walk != null && resolveOwner() != walk.owner.player) throw outside("owner_object_changed_with_walk_target");
        if (look != null && resolveOwner() != look.player) throw outside("owner_object_changed_with_look_target");
    }

    private boolean current() {
        return runtime != null && token != null
            && world != null
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
            && world.getTotalWorldTime() == worldTick
            && maid.ticksExisted == entityTick
            && Objects.equals(ownerId, maid.func_152113_b());
    }

    private void guard() {
        if (!current()) throw outside("stale_actor_context");
        if (maid.getNavigator() != navigator) throw outside("navigator_replaced");
        if (contract.homeMode || contract.goingToBreath) throw outside("home_or_breathing_not_admitted");
        if (maid.isSitting() || maid.ridingEntity != null
            || maid.getLeashed()
            || maid.hasPose(MaidPose.SLEEPING)
            || maid.getAttackTarget() != null) throw outside("movement_or_competing_action_outside_contract");
    }

    private void verifyDry(BlockPos target) {
        guard();
        if (maid.width != 0.6F || maid.height != 1.5F
            || maid.yOffset != 0
            || maid.ySize != 0
            || !Double.isFinite(maid.posX)
            || !Double.isFinite(maid.posZ)
            || !Double.isFinite(maid.posY)
            || maid.posY != Math.floor(maid.posY)
            || maid.boundingBox.minY != maid.posY
            || target.y != MathHelper.floor_double(maid.posY)) throw outside("fixed_size_same_integer_y_required");
        int x = MathHelper.floor_double(maid.posX), y = target.y, z = MathHelper.floor_double(maid.posZ);
        if (!world.blockExists(x, y - 1, z) || !world.blockExists(target.x, target.y, target.z))
            throw outside("dry_domain_unloaded");
        if (world.getBlock(x, y - 1, z) != Blocks.stone || world.getBlock(x, y, z) != Blocks.air
            || world.getBlock(x, y + 1, z) != Blocks.air) throw outside("maid_not_on_dry_stone");
        contract.domain.verify(maid, target);
        navigationLease();
    }

    private void navigationLease() {
        guard();
        PathEntity actual = navigator.getPath();
        if (actual != ownedPath) {
            throw outside(actual == null ? "navigation_disappeared_cause_unknown" : "foreign_navigation_path");
        }
    }

    private EntityPlayerMP resolveOwner() {
        String id = maid.func_152113_b();
        if (id == null || id.isEmpty()) return null;
        final UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw outside("invalid_owner_uuid");
        }
        var server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) throw outside("server_player_list_missing");
        EntityPlayerMP found = null;
        for (Object item : server.getConfigurationManager().playerEntityList) {
            if (item instanceof EntityPlayerMP player && uuid.equals(player.getUniqueID())) {
                if (found != null) throw outside("duplicate_online_owner_uuid");
                found = player;
            }
        }
        return found;
    }

    @Override
    public Owner getOwner() {
        guard();
        var owner = resolveOwner();
        return owner == null ? null : new OwnerView(owner);
    }

    public Object getWorldIdentity() {
        guard();
        return world;
    }

    public float getRestrictRadius() {
        guard();
        return contract.radius;
    }

    public boolean isGoingToBreath() {
        guard();
        return contract.goingToBreath;
    }

    public boolean isHomeModeEnable() {
        guard();
        return contract.homeMode;
    }

    public boolean canBrainMoving() {
        guard();
        return !maid.isSitting() && maid.ridingEntity == null && !maid.hasPose(MaidPose.SLEEPING) && !maid.getLeashed();
    }

    public boolean closerThan(Owner owner, double distance) {
        var view = owner(owner);
        return maid.getDistanceSqToEntity(view.player) < distance * distance;
    }

    public boolean ownerIsWalkTarget(Owner owner) {
        var view = owner(owner);
        return walk != null && walk.owner.player == view.player;
    }

    public void setWalkAndLookTargetMemories(Owner owner, float speedModifier, int stopDistance) {
        var view = owner(owner);
        look = view;
        walk = new OwnerWalk(view, speedModifier, stopDistance);
    }

    public boolean teleportToOwner(Owner owner) {
        var view = owner(owner);
        navigationLease();
        lastTeleport = Rc2FollowTeleport
            .tryTeleport(maid, view.player, maid.getRNG()::nextInt, new Rc2FollowTeleport.Access() {

                public boolean isCurrent() {
                    return current();
                }

                public boolean canOwnNavigation() {
                    return maid.getNavigator() == navigator && navigator.getPath() == ownedPath;
                }

                public void stopOwnedNavigation() {
                    stopNavigation();
                }

                public void clearSuccessfulTeleportMemories() {
                    guard();
                    walk = null;
                    look = null;
                    pathMemory = null;
                }
            });
        if (lastTeleport.status == Rc2FollowTeleport.Status.SUCCESS) return true;
        if (lastTeleport.status == Rc2FollowTeleport.Status.EXHAUSTED) return false;
        throw outside("teleport_" + lastTeleport.status + ":" + lastTeleport.diagnostic);
    }

    public void resetNavigation() {
        stopNavigation();
    }

    public void setGoingToBreath(boolean value) {
        guard();
        if (value) throw outside("breathing_not_admitted");
        contract.goingToBreath = false;
    }

    public void eraseTargetPos() {
        throw outside("target_pos_memory_not_admitted");
    }

    public void stopFollowingTask() {
        guard();
        followStop = true;
    }

    boolean consumeFollowStop() {
        boolean stopped = followStop;
        followStop = false;
        return stopped;
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

    public void setPathMemory(Path path) {
        guard();
        pathMemory = path == null ? null : nativePath(path);
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
        guard();
        navigationLease();
        if (accuracy != 0 || walk == null) throw outside("only_owner_walk_accuracy_zero_admitted");
        verifyDry(target);
        lastCreation = planner.create(maid, target.x, target.y, target.z, walk.speed);
        guard();
        if (!lastCreation.queryExecuted) throw outside("native_query_rejected:" + lastCreation.diagnostic);
        if (lastCreation.nativeTarget == null || lastCreation.nativeTarget.x != target.x
            || lastCreation.nativeTarget.y != target.y
            || lastCreation.nativeTarget.z != target.z)
            throw outside("native_target_rounding_differs_from_modern_block_target");
        if (lastCreation.path == null) return null;
        boolean reaches;
        if (lastCreation.endpointRelation == EndpointRelation.MATCHES_NATIVE_TARGET) reaches = true;
        else if (lastCreation.endpointRelation == EndpointRelation.DIFFERS_FROM_NATIVE_TARGET) reaches = false;
        else throw outside("native_endpoint_unclassified:" + lastCreation.endpointRelation);
        for (int i = 0; i < lastCreation.pathLength; i++) {
            if (lastCreation.path.getPathPointFromIndex(i).yCoord != target.y)
                throw outside("vertical_path_not_admitted");
        }
        return new NativePath(lastCreation.path, lastCreation, reaches, token);
    }

    public Path createPath(Vec3 target, int accuracy) {
        throw outside("fallback_path_not_admitted");
    }

    public Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle) {
        throw outside("modern_fallback_not_implemented");
    }

    public boolean navigationIsStuck() {
        throw outside("native_stuck_cause_unknown");
    }

    public Path navigationPath() {
        navigationLease();
        return navigationView;
    }

    public boolean navigationIsDone() {
        navigationLease();
        return navigator.noPath();
    }

    public boolean moveTo(Path path, double speedModifier) {
        navigationLease();
        var requested = nativePath(path);
        if (speedModifier != requested.creation.speed) throw outside("path_speed_changed_after_creation");
        PathEntity previous = ownedPath;
        try {
            lastInstallation = planner.install(maid, requested.creation);
        } catch (RuntimeException | Error failure) {
            rememberInstalled(requested, previous);
            throw failure;
        }
        rememberInstalled(requested, previous);
        if (lastInstallation.contextChanged) throw outside("path_install_context_changed");
        guard();
        navigationLease();
        if (!lastInstallation.nativeSetPathCalled || ownedPath == null) throw outside("native_install_missing_path");
        return lastInstallation.started;
    }

    private void rememberInstalled(NativePath requested, PathEntity previous) {
        PathEntity actual = navigator.getPath();
        if (actual == requested.raw || actual != null && actual == previous) {
            ownedPath = actual;
            navigationView = new NativePath(actual, requested.creation, requested.reaches, requested.lease);
        }
    }

    public void stopNavigation() {
        navigationLease();
        if (ownedPath != null) navigator.clearPathEntity();
        ownedPath = null;
        navigationView = null;
    }

    /** 失效后的清理不要求旧token仍current，也不覆盖后来替换的路径 */
    public void cancelOwnedResources() {
        try {
            if (ownedPath != null && navigator.getPath() == ownedPath) navigator.clearPathEntity();
        } finally {
            ownedPath = null;
            navigationView = null;
            walk = null;
            look = null;
            pathMemory = null;
            cantReachSince = null;
            followStop = false;
        }
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

    public boolean hasLookTarget() {
        return look != null;
    }

    /** 本组件只使用RC2当前LOS，不冒充原版sensor的候选范围、刷新相位或缓存 */
    public String getLookVisibilitySource() {
        return "CURRENT_RC2_LOS";
    }

    public boolean isLookTargetRegistered() {
        guard();
        return true;
    }

    public boolean isCurrentLookTargetVisible() {
        guard();
        if (look == null) return false;
        look.check();
        boolean visible = look.player.worldObj == world && look.player.isEntityAlive()
            && maid.getEntitySenses()
                .canSee(look.player);
        guard();
        return visible;
    }

    public void lookAtCurrentTargetIfPresent() {
        guard();
        if (look == null) return;
        look.check();
        double x = look.player.posX, y = look.player.posY + look.player.getEyeHeight(), z = look.player.posZ;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw outside("non_finite_look_target");
        guard();
        maid.getLookHelper()
            .setLookPosition(x, y, z, 10.0F, maid.getVerticalFaceSpeed());
    }

    /** 原LookAtTargetSink.stop只擦LOOK，不能改写已经提交或外来helper请求 */
    public void eraseLookTarget() {
        guard();
        look = null;
    }

    public MaidBoundedPathPlanner.Creation getLastCreation() {
        return lastCreation;
    }

    public MaidBoundedPathPlanner.Installation getLastInstallation() {
        return lastInstallation;
    }

    public Rc2FollowTeleport.Result getLastTeleport() {
        return lastTeleport;
    }

    private OwnerView owner(Owner value) {
        guard();
        if (!(value instanceof OwnerView view) || view.actor() != this) throw outside("foreign_owner_view");
        view.check();
        return view;
    }

    private NativePath nativePath(Path value) {
        if (!(value instanceof NativePath path) || path.actor() != this || path.lease != token)
            throw outside("foreign_or_stale_path_view");
        return path;
    }

    private static OutsideContractException outside(String reason) {
        return new OutsideContractException(reason);
    }

    private final class OwnerView implements Owner {

        private final transient EntityPlayerMP player;

        OwnerView(EntityPlayerMP player) {
            this.player = player;
        }

        Rc2FollowCoreActor actor() {
            return Rc2FollowCoreActor.this;
        }

        void check() {
            guard();
            if (resolveOwner() != player) throw outside("owner_object_not_online");
        }

        public Object getWorldIdentity() {
            check();
            return player.worldObj;
        }

        public boolean isSpectator() {
            check();
            return player.theItemInWorldManager.getGameType()
                .getID() == 3;
        }

        public boolean isDeadOrDying() {
            check();
            return player.isDead || !player.isEntityAlive();
        }

        public boolean isUnderWater() {
            check();
            return player.isInsideOfMaterial(Material.water);
        }
    }

    private final class OwnerWalk implements WalkTarget {

        private final OwnerView owner;
        private final float speed;
        private final int closeEnough;

        OwnerWalk(OwnerView owner, float speed, int closeEnough) {
            this.owner = owner;
            this.speed = speed;
            this.closeEnough = closeEnough;
        }

        public BlockPos currentBlockPosition() {
            owner.check();
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

    private final class NativePath implements Path {

        private final transient PathEntity raw;
        private final transient MaidBoundedPathPlanner.Creation creation;
        private final transient Token lease;
        private final boolean reaches;

        NativePath(PathEntity raw, MaidBoundedPathPlanner.Creation creation, boolean reaches, Token lease) {
            this.raw = raw;
            this.creation = creation;
            this.reaches = reaches;
            this.lease = lease;
        }

        Rc2FollowCoreActor actor() {
            return Rc2FollowCoreActor.this;
        }

        public Object getIdentity() {
            return raw;
        }

        public boolean canReach() {
            guard();
            nativePath(this);
            return reaches;
        }
    }
}
