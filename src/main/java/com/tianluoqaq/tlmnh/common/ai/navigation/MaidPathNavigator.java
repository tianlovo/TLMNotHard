package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathContext.floor;
import static com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.control.MaidMoveControl;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidNodeEvaluator;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidUnderWaterNodeEvaluator;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidNavigationManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatDiagnosticScope;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatQueuedMove;
import com.tianluoqaq.tlmnh.common.ai.navigation.eval.MaidNativePathRegion;
import com.tianluoqaq.tlmnh.common.ai.navigation.eval.MaidPathSearch;
import com.tianluoqaq.tlmnh.common.ai.navigation.eval.MaidWalkEvaluator;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Navigation;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathRegion;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathType;

/** One action-facing navigator, with independent modern ground/water navigation states */
public final class MaidPathNavigator extends PathNavigate implements MaidNavigationActor {

    private final EntityMaid maid;
    private final float[] costs = MaidPathType.maidCosts();
    private final State ground = new State(false), water = new State(true);
    private State active;
    private final MaidNavigationManager manager;
    private Point walkTarget;
    private long generation;
    private boolean avoidSun;
    private Object brainOwner;
    private Object brainLease;
    private BrainTarget brainTarget;
    private boolean tickingMode;
    private Stop lastStop;
    private long searches, budgetDeferrals;

    /** 动态 WALK_TARGET 由持有者实时读取，导航管理器按需动态解析当前目标坐标 */
    public interface BrainTarget {

        boolean isCurrent();

        Point currentPosition();

        /** 原生follow/moveHelper写入前的窄战斗租约检查，默认路径不改变 */
        default boolean beforeNativeNavigation(PathEntity path) {
            return true;
        }

        default boolean holdNativeWaypoint(PathEntity path) {
            return false;
        }

        default MaidCombatMoveDispatch nativeMoveDispatch(PathEntity path, double x, double y, double z, double speed) {
            return MaidCombatMoveDispatch.DEFAULT;
        }

        /** 仅复制本次真正写入的纯来源，不调用活帧或世界 */
        default void ordinaryMoveDispatched(PathEntity path, long slotRevision) {}

        default void ownedMoveDispatchRejected(PathEntity path) {}

        default void ownedMoveDispatched(PathEntity path, CombatQueuedMove command, long slotRevision) {}
    }

    public enum StopCause {
        EXPLICIT,
        MODE_CHANGE,
        WATCHDOG,
        TIMEOUT
    }

    public static final class Stop {

        public final PathEntity path;
        public final StopCause cause;
        public final long worldTick;

        private Stop(PathEntity path, StopCause cause, long worldTick) {
            this.path = path;
            this.cause = cause;
            this.worldTick = worldTick;
        }
    }

    /** 创建与安装解耦；模式交接后自动作废前置状态下的待安装路径请求 */
    public static final class BrainPath {

        public final MaidModernPath path;
        private final MaidPathNavigator navigator;
        private final Object owner;
        private final Object lease;
        private final long generation, worldTick;
        private final int entityTick;

        private BrainPath(MaidPathNavigator navigator, Object owner, MaidModernPath path) {
            this.navigator = navigator;
            this.owner = owner;
            lease = navigator.brainLease;
            this.path = path;
            generation = navigator.generation;
            worldTick = navigator.maid.worldObj.getTotalWorldTime();
            entityTick = navigator.maid.ticksExisted;
        }
    }

    public MaidPathNavigator(EntityMaid maid, World world) {
        super(maid, world);
        this.maid = maid;
        manager = new MaidNavigationManager(this);
        setCanSwim(true);
    }

    public MaidNavigationManager getManager() {
        return manager;
    }

    public long generation() {
        return generation;
    }

    public boolean usesWaterNavigation() {
        return active == water;
    }

    public void tickMode() {
        tickingMode = true;
        try {
            manager.tick();
        } finally {
            tickingMode = false;
        }
    }

    public void resetNavigation() {
        manager.resetNavigation();
    }

    /** 清理生命周期状态时，只重置已无任何路线持有者的管理器 */
    public void resetNavigationIfIdle() {
        if (ground.path == null && water.path == null) manager.resetNavigation();
    }

    private MaidNativePathRegion region() {
        return new MaidNativePathRegion(maid.worldObj, maid);
    }

    public boolean claimBrainTarget(Object owner, Object lease, BrainTarget target) {
        Objects.requireNonNull(owner, MaidNavigationIds.ID_OWNER);
        Objects.requireNonNull(lease, MaidNavigationIds.ID_LEASE);
        Objects.requireNonNull(target, MaidNavigationIds.ID_TARGET);
        if (brainOwner != null && brainOwner != owner) return false;
        brainOwner = owner;
        brainLease = lease;
        brainTarget = target;
        return true;
    }

    public void releaseBrainTarget(Object owner) {
        if (brainOwner == owner) {
            brainOwner = null;
            brainLease = null;
            brainTarget = null;
        }
    }

    public boolean isBrainTargetOwner(Object owner) {
        return owner != null && brainOwner == owner;
    }

    private boolean brainCurrent() {
        return brainTarget != null && brainTarget.isCurrent();
    }

    private void requireBrainOwner(Object owner) {
        if (owner == null || brainOwner != owner || !brainCurrent())
            throw new IllegalStateException(MaidNavigationDiagnostics.MSG_FOREIGN_OR_EXPIRED_BRAIN_NAVIGATION);
    }

    /** 原现代导航API：先核可更新条件，再复用同目标活路线，否则用实际FOLLOW_RANGE创建 */
    public BrainPath createBrainPath(Object owner, int x, int y, int z, int accuracy) {
        requireBrainOwner(owner);
        var path = active.brainQuery(new Point(x, y, z), accuracy);
        return path == null ? null : new BrainPath(this, owner, path);
    }

    /** 集合重载与现代PathNavigation一致，不套用Ground单坐标重载的目标归一化 */
    public BrainPath createBrainPath(Object owner, Collection<Point> targets, int accuracy) {
        requireBrainOwner(owner);
        var path = active.brainQuery(targets, accuracy);
        return path == null ? null : new BrainPath(this, owner, path);
    }

    public boolean installBrainPath(Object owner, BrainPath created, double speed) {
        requireBrainOwner(owner);
        if (created == null) return active.install(null, speed, false);
        if (created.navigator != this || created.owner != owner
            || created.lease != brainLease
            || created.generation != generation
            || created.worldTick != maid.worldObj.getTotalWorldTime()
            || created.entityTick != maid.ticksExisted
            || created.path.result.water != active.water)
            throw new IllegalStateException(MaidNavigationDiagnostics.MSG_BRAIN_PATH_CREATION_CONTEXT_CHANGED);
        return active.install(created.path, speed, false);
    }

    /** 仅显式QA作用域取得上一次安装的纯值，不读取World或重新安装 */
    public String combatInstallDiagnostic() {
        var scope = CombatDiagnosticScope.current(maid.worldObj);
        return scope == null ? null : (String) scope.get(active);
    }

    private static String diagnosticRaw(PathEntity path) {
        if (path == null) return MaidNavigationIds.ID_NULL;
        if (!(path instanceof MaidModernPath)) return MaidNavigationIds.KEY_NOT_MODERN_NOT_EVALUATED;
        int count = path.getCurrentPathLength(), index = path.getCurrentPathIndex();
        StringBuilder out = new StringBuilder(MaidNavigationIds.ID_RAW).append(System.identityHashCode(path))
            .append('/')
            .append(count)
            .append('/')
            .append(index)
            .append(MaidNavigationIds.ID_FINISHED)
            .append(path.isFinished())
            .append(MaidNavigationIds.ID_CANREACH)
            .append(((MaidModernPath) path).canReach());
        if (count >= 0 && count <= MaidNavigationTuning.MIN_COUNT_THRESHOLD) for (int i = 0; i < count; i++) {
            var p = path.getPathPointFromIndex(i);
            out.append('[')
                .append(p.xCoord)
                .append(',')
                .append(p.yCoord)
                .append(',')
                .append(p.zCoord)
                .append(']');
        }
        else out.append(MaidNavigationIds.ID_POINTS_NOT_EVALUATED_TOO_LONG);
        return out.toString();
    }

    public Stop lastStop() {
        return lastStop;
    }

    public long searchCount() {
        return searches;
    }

    public long budgetDeferrals() {
        return budgetDeferrals;
    }

    private boolean enhancedBudgeted() {
        var policy = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        return policy != null && policy.isReady()
            && policy.getProfile()
                .isEnhanced();
    }

    /** Pure query used by the guarded production create/install boundary */
    public MaidModernPath createPath(int x, int y, int z, float range, int accuracy) {
        return query(active, x, y, z, range, accuracy);
    }

    public MaidAmphibiousPath createWaterPath(int x, int y, int z, float range, int accuracy) {
        return (MaidAmphibiousPath) query(water, x, y, z, range, accuracy);
    }

    private MaidModernPath query(State state, int x, int y, int z, float range, int accuracy) {
        var region = region();
        var requested = new Point(x, y, z);
        var normalized = normalize(state, requested, region);
        return query(state, Collections.singleton(normalized), range, accuracy, requested);
    }

    private MaidModernPath query(State state, Collection<Point> targets, float range, int accuracy, Point requested) {
        if (targets.isEmpty()) return null;
        // 增强root和水陆切换共用此边界；F保留原无预算查询合同
        if (enhancedBudgeted() && !MaidWorldNavigationBudget.acquire(maid)) {
            budgetDeferrals++;
            return null;
        }
        searches++;
        var result = MaidPathSearch.find(
            context(region()),
            state.water,
            targets,
            range,
            accuracy,
            floor(getPathSearchRange() * MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_PARAM));
        if (result == null) return null;
        if (requested == null) requested = new Point(result.targetX, result.targetY, result.targetZ);
        return state.water ? new MaidAmphibiousPath(result, requested) : new MaidModernPath(result, requested);
    }

    private Point normalize(State state, Point requested, MaidNativePathRegion region) {
        int x = requested.x, y = requested.y, z = requested.z;
        if (!state.water) {
            if (region.air(x, y, z)) {
                int below = y - 1;
                while (below > region.minY() && region.air(x, below, z)) below--;
                if (below > region.minY()) y = below + 1;
                else {
                    while (below < region.maxY() && region.air(x, below, z)) below++;
                    y = below;
                }
            }
            if (region.solid(x, y, z)) {
                y++;
                while (y < region.maxY() && region.solid(x, y, z)) y++;
            }
        }
        return new Point(x, y, z);
    }

    public MaidPathContext context(MaidPathRegion region) {
        return new MaidPathContext(
            region,
            maid.posX,
            maid.boundingBox.minY,
            maid.posZ,
            maid.width,
            maid.height,
            maid.stepHeight,
            maid.getMaxSafePointTries(),
            maid.onGround,
            costs);
    }

    public MaidWalkEvaluator evaluator() {
        return active.water ? new MaidUnderWaterNodeEvaluator(context(region()))
            : new MaidNodeEvaluator(context(region()));
    }

    /** 调用方需在当前任务查询结束时显式关闭，快照数据仅在当前 tick 内有效 */
    public MaidPathFindingBFS createArrivalMap(Point center, float range, int verticalRange) {
        return new MaidPathFindingBFS(evaluator(), center, range, verticalRange);
    }

    public boolean canUpdatePath() {
        return active.canUpdate();
    }

    public float pathfindingCost(MaidPathType type) {
        return costs[type.ordinal()];
    }

    public boolean isStableDestination(int x, int y, int z) {
        if (!maid.worldObj.blockExists(x, y - 1, z)) return false;
        return active.water ? !maid.worldObj.isAirBlock(x, y - 1, z)
            : maid.worldObj.getBlock(x, y - 1, z)
                .isOpaqueCube();
    }

    @Override
    public PathEntity getPath() {
        return active == null ? null : active.path;
    }

    @Override
    public boolean noPath() {
        return getPath() == null || getPath().isFinished();
    }

    @Override
    public void clearPathEntity() {
        if (active != null) active.stop();
        walkTarget = null;
        super.clearPathEntity();
    }

    @Override
    public boolean setPath(PathEntity path, double speed) {
        boolean installed = active.install(path, speed);
        if (installed && path instanceof MaidModernPath modern) walkTarget = modern.requestedTarget;
        return installed;
    }

    @Override
    public void setSpeed(double speed) {
        super.setSpeed(speed);
        if (active != null) active.speed = speed;
    }

    @Override
    public void setAvoidSun(boolean value) {
        super.setAvoidSun(value);
        avoidSun = value;
    }

    @Override
    public PathEntity getPathToXYZ(double x, double y, double z) {
        if (!canUpdatePath()) return null;
        var path = query(
            active,
            floor(x),
            (int) y,
            floor(z),
            getPathSearchRange(),
            MaidNavigationTuning.QUERY_SINGLE_UNIT);
        active.resetTimeout();
        return path;
    }

    @Override
    public PathEntity getPathToEntityLiving(Entity entity) {
        return getPathToXYZ(entity.posX, entity.posY, entity.posZ);
    }

    public boolean isModernStuck() {
        return active.stuck;
    }

    public double speedModifier() {
        return active.speed;
    }

    @Override
    public void onUpdateNavigation() {
        active.tick++;
        if (active.path == null || active.path.isFinished()) return;
        if (brainTarget != null && !brainTarget.beforeNativeNavigation(active.path)) return;
        if (!(active.path instanceof MaidModernPath modern)) {
            super.onUpdateNavigation();
            return;
        }
        if (canUpdatePath()) active.follow(modern);
        else {
            var position = active.position();
            var next = modern.getPosition(maid);
            if (position.yCoord > next.yCoord && !maid.onGround
                && floor(position.xCoord) == floor(next.xCoord)
                && floor(position.zCoord) == floor(next.zCoord)) modern.incrementPathIndex();
        }
        if (!noPath()) {
            var target = getPath().getPosition(maid);
            double y = active.water
                || region().air(floor(target.xCoord), floor(target.yCoord) - 1, floor(target.zCoord)) ? target.yCoord
                    : region().floor(floor(target.xCoord), floor(target.yCoord), floor(target.zCoord));
            var raw = getPath();
            var dispatch = brainTarget == null ? MaidCombatMoveDispatch.DEFAULT
                : brainTarget.nativeMoveDispatch(raw, target.xCoord, y, target.zCoord, active.speed);
            if (dispatch.kind == MaidCombatMoveDispatch.Kind.DEFAULT) {
                maid.getMoveHelper()
                    .setMoveTo(target.xCoord, y, target.zCoord, active.speed);
                if (brainTarget != null && maid.getMoveHelper() instanceof MaidMoveControl control)
                    brainTarget.ordinaryMoveDispatched(raw, control.combatSlotRevision());
            } else if (dispatch.kind == MaidCombatMoveDispatch.Kind.OWNED) {
                if (maid.getMoveHelper() instanceof MaidMoveControl control
                    && control.setOwnedMoveTo(dispatch.command, target.xCoord, y, target.zCoord, active.speed)) {
                    if (brainTarget != null)
                        brainTarget.ownedMoveDispatched(raw, dispatch.command, control.combatSlotRevision());
                } else if (brainTarget != null) brainTarget.ownedMoveDispatchRejected(raw);
            }
        }
    }

    @Override
    public boolean effectiveServerAi() {
        return !maid.worldObj.isRemote && maid.isEntityAlive()
            && !maid.getBrainCoordinator()
                .isSuspended();
    }

    @Override
    public boolean inWater() {
        return maid.isInWater();
    }

    @Override
    public boolean underWater() {
        return maid.isInsideOfMaterial(Material.water);
    }

    @Override
    public boolean water(int x, int y, int z) {
        return region().water(x, y, z);
    }

    @Override
    public boolean air(int x, int y, int z) {
        return region().air(x, y, z);
    }

    @Override
    public boolean landPassable(int x, int y, int z) {
        return region().landPassable(x, y, z);
    }

    @Override
    public Point position() {
        return new Point(floor(maid.posX), floor(maid.posY), floor(maid.posZ));
    }

    @Override
    public Point walkTarget() {
        return brainTarget == null ? walkTarget : brainCurrent() ? brainTarget.currentPosition() : null;
    }

    @Override
    public MaidSwimManager swim() {
        return maid.getAquaticController()
            .getManager();
    }

    @Override
    public Navigation ground() {
        return ground;
    }

    @Override
    public Navigation waterNavigation() {
        return water;
    }

    @Override
    public Navigation current() {
        return active;
    }

    @Override
    public void activate(Navigation navigation) {
        if (navigation != ground && navigation != water)
            throw new IllegalArgumentException(MaidNavigationDiagnostics.MSG_FOREIGN_NAVIGATION_STATE);
        if (active != navigation) {
            active = (State) navigation;
            generation++;
            lastStop = null;
        }
    }

    private final class State implements Navigation {

        final boolean water;
        PathEntity path;
        Point targetPos;
        LegacyPath legacy;
        double speed;
        int tick, lastStuckCheck, timeoutX, timeoutY, timeoutZ;
        long timeoutTimer, lastTimeoutCheck;
        double timeoutLimit;
        boolean stuck;
        Vec3 lastStuckPos = Vec3.createVectorHelper(
            MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE,
            MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE,
            MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE);

        State(boolean water) {
            this.water = water;
        }

        @Override
        public MaidNavigationActor.Path path() {
            return path instanceof MaidModernPath modern ? modern : legacy;
        }

        @Override
        public boolean done() {
            return path == null || path.isFinished();
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void stop() {
            stop(tickingMode ? StopCause.MODE_CHANGE : StopCause.EXPLICIT);
        }

        void stop(StopCause cause) {
            if (active == this && path != null) lastStop = new Stop(path, cause, maid.worldObj.getTotalWorldTime());
            if (path != null && !(path instanceof MaidModernPath)) MaidPathNavigator.super.clearPathEntity();
            path = null;
            legacy = null;
        }

        MaidModernPath brainQuery(Point target, int accuracy) {
            var normalized = normalize(this, target, region());
            return brainQuery(Collections.singleton(normalized), accuracy);
        }

        MaidModernPath brainQuery(Collection<Point> targets, int accuracy) {
            if (targets.isEmpty() || maid.posY < region().minY() || !canUpdate()) return null;
            if (path instanceof MaidModernPath modern && !path.isFinished()
                && targetPos != null
                && targets.contains(targetPos)) return modern;
            var created = query(this, targets, getPathSearchRange(), accuracy, null);
            if (created != null) {
                // 原版 createPath 立即记录最新查询目标，真实反映寻路意图的探索端点
                targetPos = new Point(created.result.targetX, created.result.targetY, created.result.targetZ);
                resetTimeout();
            }
            return created;
        }

        boolean canUpdate() {
            return water || maid.onGround || maid.isInWater() || maid.handleLavaMovement() || maid.ridingEntity != null;
        }

        @Override
        public MaidNavigationActor.Path create(Point target) {
            if (brainCurrent()) return brainQuery(target, MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE);
            if (path instanceof MaidModernPath modern && !path.isFinished()
                && modern.result.targetX == target.x
                && modern.result.targetY == target.y
                && modern.result.targetZ == target.z) return modern;
            if (!enhancedBudgeted() && !MaidWorldNavigationBudget.acquire(maid)) return null;
            var created = query(
                this,
                target.x,
                target.y,
                target.z,
                Math.min(MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_PARAM, getPathSearchRange()),
                MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE);
            resetTimeout();
            return created;
        }

        @Override
        public boolean moveTo(MaidNavigationActor.Path candidate, double speed) {
            if (!(candidate instanceof MaidModernPath modern)) return false;
            if (active != this && active.path != null) modern.continueCancellation(active.path);
            return install(modern, speed, !brainCurrent());
        }

        boolean install(PathEntity candidate, double speed) {
            return install(candidate, speed, true);
        }

        boolean install(PathEntity candidate, double speed, boolean resetTimer) {
            var diagnosticScope = CombatDiagnosticScope.current(maid.worldObj);
            if (diagnosticScope != null) {
                Object ownerLog = diagnosticScope.get(brainOwner);
                if (!(ownerLog instanceof CombatDiagnosticScope.Log log)) diagnosticScope = null;
                else if (!log.hasRemainingCurrentCapacity()) {
                    diagnosticScope.put(this, MaidNavigationIds.KEY_NOT_EVALUATED_LOG_FULL);
                    diagnosticScope = null;
                }
            }
            String diagnosticBefore = diagnosticScope == null ? null : diagnosticRaw(path);
            String diagnosticCandidate = diagnosticScope == null ? null : diagnosticRaw(candidate);
            int same = MaidNavigationTuning.UNSET_OR_NOT_FOUND,
                transformations = MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE;
            if (candidate == null) {
                stop();
                traceInstall(
                    diagnosticScope,
                    diagnosticCandidate,
                    diagnosticBefore,
                    same,
                    transformations,
                    MaidNavigationIds.KEY_NULL,
                    false);
                return false;
            }
            if (candidate instanceof MaidModernPath) {
                if ((same = candidate.isSamePath(path) ? MaidNavigationTuning.QUERY_SINGLE_UNIT
                    : MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE) == 0 || path.isFinished())
                    path = candidate;
                if (path.isFinished()) {
                    traceInstall(
                        diagnosticScope,
                        diagnosticCandidate,
                        diagnosticBefore,
                        same,
                        transformations,
                        MaidNavigationIds.KEY_FINISHED,
                        false);
                    return false;
                }
                MaidPathNavigator.super.clearPathEntity();
                legacy = null;
                var modern = (MaidModernPath) path;
                for (int i = 0; i < modern.getCurrentPathLength(); i++) {
                    var node = modern.getPathPointFromIndex(i);
                    if (maid.worldObj.getBlock(node.xCoord, node.yCoord, node.zCoord) == Blocks.cauldron) {
                        transformations |= MaidNavigationTuning.QUERY_SINGLE_UNIT;
                        modern.raise(i, node.yCoord + 1);
                        if (i + 1 < modern.getCurrentPathLength()
                            && node.yCoord >= modern.getPathPointFromIndex(i + 1).yCoord)
                            modern.raise(i + 1, node.yCoord + 1);
                    }
                }
                if (!water && avoidSun
                    && !maid.worldObj.canBlockSeeTheSky(
                        floor(maid.posX),
                        floor(maid.posY + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR),
                        floor(maid.posZ))) {
                    for (int i = 0; i < modern.getCurrentPathLength(); i++) {
                        var n = modern.getPathPointFromIndex(i);
                        if (maid.worldObj.canBlockSeeTheSky(n.xCoord, n.yCoord, n.zCoord)) {
                            transformations |= MaidNavigationTuning.MIN_COUNT_THRESHOLD;
                            modern.setCurrentPathLength(i);
                            break;
                        }
                    }
                }
            } else {
                if (!MaidPathNavigator.super.setPath(candidate, speed)) {
                    traceInstall(
                        diagnosticScope,
                        diagnosticCandidate,
                        diagnosticBefore,
                        same,
                        transformations,
                        MaidNavigationIds.KEY_LEGACY_INSTALL,
                        false);
                    return false;
                }
                path = MaidPathNavigator.super.getPath();
                legacy = new LegacyPath(path);
            }
            if (path.getCurrentPathLength() <= 0) {
                traceInstall(
                    diagnosticScope,
                    diagnosticCandidate,
                    diagnosticBefore,
                    same,
                    transformations,
                    MaidNavigationIds.KEY_ZERO_LENGTH,
                    false);
                return false;
            }
            this.speed = speed;
            lastStuckCheck = tick;
            lastStuckPos = position();
            if (active == this) lastStop = null;
            if (resetTimer) resetTimeout();
            traceInstall(
                diagnosticScope,
                diagnosticCandidate,
                diagnosticBefore,
                same,
                transformations,
                MaidNavigationIds.KEY_ACCEPTED,
                true);
            return true;
        }

        private void traceInstall(CombatDiagnosticScope scope, String candidate, String before, int same,
            int transformations, String reason, boolean accepted) {
            if (scope != null) scope.put(
                this,
                MaidNavigationIds.ID_CANDIDATE + candidate
                    + MaidNavigationIds.ID_BEFORE
                    + before
                    + MaidNavigationIds.ID_SELECTED
                    + diagnosticRaw(path)
                    + MaidNavigationIds.ID_SAME
                    + same
                    + MaidNavigationIds.ID_TRANSFORM
                    + transformations
                    + MaidNavigationIds.ID_REASON
                    + reason
                    + MaidNavigationIds.ID_ACCEPTED
                    + accepted);
        }

        Vec3 position() {
            double y = maid.posY + maid.height * MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR;
            if (!water) {
                int surface = floor(maid.posY);
                if (maid.isInWater()) {
                    int steps = 0;
                    while (region().water(floor(maid.posX), surface, floor(maid.posZ))) {
                        surface++;
                        if (++steps > MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_PARAM) {
                            surface = floor(maid.posY);
                            break;
                        }
                    }
                } else surface = floor(maid.posY + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR);
                y = surface;
            }
            return Vec3.createVectorHelper(maid.posX, y, maid.posZ);
        }

        void follow(MaidModernPath route) {
            var position = position();
            if (brainTarget != null && brainTarget.holdNativeWaypoint(route)) {
                detectStuck(position);
                return;
            }
            var node = route.getPathPointFromIndex(route.getCurrentPathIndex());
            float tolerance = maid.width > MaidNavigationTuning.MAX_WIDTH_THRESHOLD
                ? maid.width / MaidNavigationTuning.MIDPOINT_DIVISOR_F
                : MaidNavigationTuning.MAX_WIDTH_THRESHOLD - maid.width / MaidNavigationTuning.MIDPOINT_DIVISOR_F;
            double offset = (double) (maid.width + MaidNavigationTuning.PATH_NAVIGATOR_OFFSET)
                / MaidNavigationTuning.MIN_COUNT_THRESHOLD;
            boolean near = Math.abs(maid.posX - (node.xCoord + offset)) <= tolerance
                && Math.abs(maid.posZ - (node.zCoord + offset)) <= tolerance
                && Math.abs(maid.posY - node.yCoord) < MaidNavigationTuning.QUERY_SINGLE_UNIT;
            var type = route.currentType();
            if (near || type != DANGER_FIRE && type != DANGER_OTHER
                && type != WALKABLE_DOOR
                && nextInDirection(route, position)) route.incrementPathIndex();
            detectStuck(position);
        }

        boolean nextInDirection(MaidModernPath route, Vec3 position) {
            int index = route.getCurrentPathIndex();
            if (index + 1 >= route.getCurrentPathLength()) return false;
            var first = route.getPathPointFromIndex(index);
            var target = Vec3.createVectorHelper(
                first.xCoord + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR,
                first.yCoord,
                first.zCoord + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR);
            if (position.squareDistanceTo(target) >= MaidNavigationTuning.PATH_NAVIGATOR_NEXT_IN_DIRECTION_PARAM)
                return false;
            if (water && (maid.isInWater() || maid.handleLavaMovement())) {
                var end = route.getPosition(maid)
                    .addVector(
                        MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE,
                        maid.height * MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR,
                        MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE);
                if (maid.worldObj.func_147447_a(position, end, false, true, false) == null) return true;
            }
            var second = route.getPathPointFromIndex(index + 1);
            var one = target.subtract(position);
            var two = Vec3
                .createVectorHelper(
                    second.xCoord + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR,
                    second.yCoord,
                    second.zCoord + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR)
                .subtract(position);
            double a = one.xCoord * one.xCoord + one.yCoord * one.yCoord + one.zCoord * one.zCoord,
                b = two.xCoord * two.xCoord + two.yCoord * two.yCoord + two.zCoord * two.zCoord;
            return (b < a || a < MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR) && one.normalize()
                .dotProduct(two.normalize()) < 0;
        }

        void detectStuck(Vec3 position) {
            if (tick - lastStuckCheck > MaidNavigationTuning.MAX_LAST_STUCK_CHECK_THRESHOLD) {
                float speed = maid.getAIMoveSpeed();
                float distance = (speed >= MaidNavigationTuning.QUERY_SINGLE_UNIT ? speed : speed * speed)
                    * MaidNavigationTuning.PATH_NAVIGATOR_DETECT_STUCK_FACTOR
                    * MaidNavigationTuning.PATH_NAVIGATOR_DETECT_STUCK_FACTOR_2;
                if (position.squareDistanceTo(lastStuckPos) < distance * distance) {
                    stuck = true;
                    stop(StopCause.WATCHDOG);
                } else stuck = false;
                lastStuckCheck = tick;
                lastStuckPos = position;
            }
            if (done()) return;
            var node = path.getPathPointFromIndex(path.getCurrentPathIndex());
            long time = maid.worldObj.getTotalWorldTime();
            if (node.xCoord == timeoutX && node.yCoord == timeoutY && node.zCoord == timeoutZ)
                timeoutTimer += time - lastTimeoutCheck;
            else {
                timeoutX = node.xCoord;
                timeoutY = node.yCoord;
                timeoutZ = node.zCoord;
                double distance = position.distanceTo(
                    Vec3.createVectorHelper(
                        timeoutX + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR,
                        timeoutY,
                        timeoutZ + MaidNavigationTuning.PATH_NAVIGATOR_FLOOR_FIRST_FACTOR));
                timeoutLimit = maid.getAIMoveSpeed() > 0
                    ? distance / maid.getAIMoveSpeed() * MaidNavigationTuning.PATH_NAVIGATOR_TIMEOUT_LIMIT
                    : MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE;
            }
            if (timeoutLimit > 0
                && timeoutTimer > timeoutLimit * MaidNavigationTuning.PATH_NAVIGATOR_DETECT_STUCK_PARAM) {
                resetTimeout();
                stop(StopCause.TIMEOUT);
            }
            lastTimeoutCheck = time;
        }

        void resetTimeout() {
            timeoutX = timeoutY = timeoutZ = MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE;
            timeoutTimer = MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE;
            timeoutLimit = MaidNavigationTuning.CREATE_VECTOR_HELPER_INITIAL_VALUE;
            stuck = false;
        }
    }

    private static final class LegacyPath implements MaidNavigationActor.Path {

        final PathEntity path;

        LegacyPath(PathEntity path) {
            this.path = path;
        }

        @Override
        public boolean canReach() {
            return false;
        }

        @Override
        public boolean done() {
            return path.isFinished();
        }

        @Override
        public int index() {
            return path.getCurrentPathIndex();
        }

        @Override
        public int size() {
            return path.getCurrentPathLength();
        }

        @Override
        public Point node(int index) {
            var p = path.getPathPointFromIndex(index);
            return new Point(p.xCoord, p.yCoord, p.zCoord);
        }

        @Override
        public Point end() {
            return size() == 0 ? null : node(size() - 1);
        }

        @Override
        public void advance() {
            path.incrementPathIndex();
        }
    }
}
