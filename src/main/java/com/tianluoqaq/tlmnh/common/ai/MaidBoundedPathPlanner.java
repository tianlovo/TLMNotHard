package com.tianluoqaq.tlmnh.common.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidPathQuery;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.Kind;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** 限制单次原版路径查询范围，不改变实体属性或原导航器的路径执行 */
public final class MaidBoundedPathPlanner {

    private static final NativeAccess NATIVE = NativeAccess.resolve(PathNavigate.class);
    private Attempt lastAttempt;
    private final boolean budgeted;

    /** 原生对照与只读探针不改变其调用节奏；生产执行器必须显式启用世界预算 */
    public MaidBoundedPathPlanner() {
        this(false);
    }

    public MaidBoundedPathPlanner(boolean budgeted) {
        this.budgeted = budgeted;
    }

    /** 即时查询/安装；预算等待不写原生导航，生产请求须核对创建时的身份与指令 */
    public PathResult request(EntityMaid maid, double x, double y, double z, double speed) {
        var result = install(maid, create(maid, x, y, z, speed), budgeted).result;
        maid.getBrainCoordinator()
            .getTelemetry()
            .navigation(
                Kind.NAV_PROCESSED,
                MaidAiIds.ID_NAVIGATION,
                "",
                MaidAiIds.ID_PLANNER_ADAPTER_FINALIZED + result,
                MaidAiTuning.NAVIGATION_INITIAL_VALUE);
        return result;
    }

    /** 只创建原生路径，不安装、撤回或修改当前路径，也不重置导航计时 */
    public Creation create(EntityMaid maid, double x, double y, double z, double speed) {
        var navigator = maid.getNavigator();
        var context = new QueryContext(maid, navigator);
        var queryTarget = new Point(MathHelper.floor_double(x), (int) y, MathHelper.floor_double(z));
        var nativeTarget = Point.nativeTarget(queryTarget, maid.width);
        float nativeRange = navigator.getPathSearchRange();
        float searchRange = Math.min(nativeRange, MaidAiTuning.MAX_SEARCH_RANGE);
        var options = NATIVE.options(navigator);
        boolean breakDoors = navigator.getCanBreakDoors(), avoidWater = navigator.getAvoidsWater();
        if (!options.supported) return creation(
            context,
            queryTarget,
            nativeTarget,
            speed,
            null,
            false,
            PathResult.ADAPTER_UNSUPPORTED,
            nativeRange,
            searchRange,
            options,
            null,
            breakDoors,
            avoidWater,
            options.diagnostic);
        if (!Float.isFinite(searchRange) || searchRange <= 0
            || !Double.isFinite(x)
            || !Double.isFinite(y)
            || !Double.isFinite(z)
            || !Double.isFinite(speed)
            || speed <= 0) {
            return creation(
                context,
                queryTarget,
                nativeTarget,
                speed,
                null,
                false,
                PathResult.UNREACHABLE,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                MaidAiIds.ID_INVALID_SEARCH_PARAMETERS);
        }
        int padding = (int) (searchRange + MaidAiTuning.BOUNDED_PATH_PADDING);
        int originX = MathHelper.floor_double(maid.posX), originZ = MathHelper.floor_double(maid.posZ);
        var chunks = maid.worldObj.getChunkProvider();
        for (int chunkX = (originX - padding) >> MaidAiTuning.CHUNK_SECTION_SHIFT; chunkX
            <= (originX + padding) >> MaidAiTuning.CHUNK_SECTION_SHIFT; chunkX++) {
            for (int chunkZ = (originZ - padding) >> MaidAiTuning.CHUNK_SECTION_SHIFT; chunkZ
                <= (originZ + padding) >> MaidAiTuning.CHUNK_SECTION_SHIFT; chunkZ++) {
                if (!chunks.chunkExists(chunkX, chunkZ)) return creation(
                    context,
                    queryTarget,
                    nativeTarget,
                    speed,
                    null,
                    false,
                    PathResult.UNLOADED,
                    nativeRange,
                    searchRange,
                    options,
                    null,
                    breakDoors,
                    avoidWater,
                    MaidAiIds.ID_CACHE_CHUNK_UNLOADED + chunkX + MaidAiIds.COMMA_DELIMITER + chunkZ);
            }
        }
        if (!maid.worldObj
            .blockExists(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z))) {
            return creation(
                context,
                queryTarget,
                nativeTarget,
                speed,
                null,
                false,
                PathResult.UNLOADED,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                MaidAiIds.ID_DESTINATION_UNLOADED);
        }
        final boolean canNavigate;
        try {
            canNavigate = navigator instanceof MaidPathNavigator modern ? modern.canUpdatePath()
                : NATIVE.canNavigate(navigator);
        } catch (RuntimeException failure) {
            return creation(
                context,
                queryTarget,
                nativeTarget,
                speed,
                null,
                false,
                PathResult.ADAPTER_UNSUPPORTED,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                failure.toString());
        }
        if (!canNavigate) return creation(
            context,
            queryTarget,
            nativeTarget,
            speed,
            null,
            false,
            PathResult.NOT_NAVIGABLE,
            nativeRange,
            searchRange,
            options,
            false,
            breakDoors,
            avoidWater,
            MaidAiIds.ID_NATIVE_CANNAVIGATE_FALSE);
        if (budgeted && !MaidWorldNavigationBudget.acquire(maid)) return creation(
            context,
            queryTarget,
            nativeTarget,
            speed,
            null,
            false,
            PathResult.WAITING_BUDGET,
            nativeRange,
            searchRange,
            options,
            true,
            breakDoors,
            avoidWater,
            MaidAiIds.ID_WAITING_WORLD_NAVIGATION_BUDGET);
        var telemetry = maid.getBrainCoordinator()
            .getTelemetry();
        boolean recording = telemetry.isEnabled();
        long elapsed;
        PathEntity path;
        telemetry.navigation(
            Kind.NAV_SUBMITTED,
            MaidAiIds.ID_NAVIGATION,
            "",
            MaidAiIds.ID_NATIVE_WORLD_PATH_QUERY,
            MaidAiTuning.NAVIGATION_INITIAL_VALUE);
        long startedAt = recording ? System.nanoTime() : MaidAiTuning.NAVIGATION_INITIAL_VALUE;
        try {
            path = MaidPathQuery.create(
                maid,
                MathHelper.floor_double(x),
                (int) y,
                MathHelper.floor_double(z),
                searchRange,
                options.enterDoors,
                breakDoors,
                avoidWater,
                options.canSwim);
        } catch (RuntimeException | Error failure) {
            telemetry.state(
                MaidAiIds.ID_NAVIGATION,
                "",
                MaidAiIds.ID_NATIVE_PATH_THREW + failure.getClass()
                    .getSimpleName());
            throw failure;
        } finally {
            elapsed = recording ? System.nanoTime() - startedAt : MaidAiTuning.NAVIGATION_INITIAL_VALUE;
            if (recording) telemetry.work(MaidAiIds.ID_NATIVE_PATH, WorkComponent.NATIVE_PATH, elapsed);
        }
        telemetry.navigation(
            Kind.NAV_RETURNED,
            MaidAiIds.ID_NAVIGATION,
            "",
            MaidAiIds.ID_PATH_NULL + (path == null),
            elapsed);
        return creation(
            context,
            queryTarget,
            nativeTarget,
            speed,
            path,
            true,
            null,
            nativeRange,
            searchRange,
            options,
            true,
            breakDoors,
            avoidWater,
            path == null ? MaidAiIds.ID_NATIVE_PATH_NULL
                : MaidAiIds.ID_NATIVE_PATH_CREATED_LENGTH + path.getCurrentPathLength());
    }

    /**
     * 显式安装阶段；调用方还须在前后核对自己的动作token和导航资源归属
     * null 路径仍会调用原生 setPath(null) 清空导航，具有状态变更副作用。
     * 创建时需具备有效的 policy context，仅响应当前上下文的路径请求
     */
    public Installation install(EntityMaid maid, Creation created) {
        return install(maid, created, true);
    }

    private Installation install(EntityMaid maid, Creation created, boolean guardContext) {
        Objects.requireNonNull(maid, MaidAiIds.ID_MAID);
        Objects.requireNonNull(created, MaidAiIds.ID_CREATED);
        if (guardContext && !created.context.matches(maid)) return installation(
            created,
            PathResult.STALE_REQUEST,
            false,
            false,
            null,
            false,
            true,
            MaidAiIds.ID_NATIVE_QUERY_CONTEXT_CHANGED);
        if (!created.queryExecuted) return installation(
            created,
            created.preflightFailure,
            false,
            false,
            null,
            false,
            false,
            created.diagnostic);
        var navigator = created.context.navigator;
        var before = navigator.getPath();
        boolean started = navigator.setPath(created.path, created.speed);
        // 原生isSamePath可能保留旧对象；动作必须认领真正安装的对象
        var actual = navigator.getPath();
        boolean reused = actual != null && actual == before;
        boolean changed = guardContext && !created.context.matches(maid);
        return installation(
            created,
            changed ? PathResult.STALE_REQUEST : started ? PathResult.STARTED : PathResult.UNREACHABLE,
            true,
            started,
            actual,
            reused,
            changed,
            changed ? MaidAiIds.ID_NATIVE_QUERY_CONTEXT_CHANGED_AFTER_INSTALL
                : created.path == null ? MaidAiIds.ID_NATIVE_PATH_NULL
                    : MaidAiIds.ID_NATIVE_SETPATH + started
                        + MaidAiIds.ID_LENGTH
                        + created.path.getCurrentPathLength());
    }

    private Creation creation(QueryContext context, Point queryTarget, Point nativeTarget, double speed,
        PathEntity path, boolean queryExecuted, PathResult preflightFailure, float nativeRange, float searchRange,
        Options options, Boolean navigable, boolean breakDoors, boolean avoidWater, String diagnostic) {
        return new Creation(
            context,
            queryTarget,
            nativeTarget,
            speed,
            path,
            queryExecuted,
            preflightFailure,
            nativeRange,
            searchRange,
            options,
            navigable,
            breakDoors,
            avoidWater,
            diagnostic);
    }

    private Installation installation(Creation created, PathResult result, boolean nativeSetPathCalled, boolean started,
        PathEntity actual, boolean reused, boolean contextChanged, String diagnostic) {
        lastAttempt = new Attempt(
            result,
            created.nativeRange,
            created.searchRange,
            created.options,
            created.navigable,
            created.breakDoors,
            created.avoidWater,
            diagnostic);
        return new Installation(
            result,
            created.path,
            actual,
            nativeSetPathCalled,
            started,
            reused,
            contextChanged,
            diagnostic);
    }

    public Attempt getLastAttempt() {
        return lastAttempt;
    }

    /** 只描述原生终点事实，不提供现代Path.canReach或导航isStuck等价结论 */
    public enum EndpointRelation {
        NOT_QUERIED,
        NULL_PATH,
        EMPTY_PATH,
        MATCHES_NATIVE_TARGET,
        DIFFERS_FROM_NATIVE_TARGET,
        UNKNOWN_TARGET
    }

    public static final class Point {

        public final int x, y, z;

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 对应PathFinder的int入口中心偏移及width/2；保持其float舍入 */
        static Point nativeTarget(Point query, float width) {
            if (!Float.isFinite(width) || width <= 0) return null;
            return new Point(
                MathHelper.floor_double(
                    (double) ((float) query.x + MaidAiTuning.CENTER_OFFSET_F)
                        - (double) (width / MaidAiTuning.MIDPOINT_DIVISOR_F)),
                MathHelper.floor_double((double) ((float) query.y + MaidAiTuning.CENTER_OFFSET_F)),
                MathHelper.floor_double(
                    (double) ((float) query.z + MaidAiTuning.CENTER_OFFSET_F)
                        - (double) (width / MaidAiTuning.MIDPOINT_DIVISOR_F)));
        }
    }

    public static final class Creation {

        private final transient QueryContext context;
        public final transient PathEntity path;
        public final boolean queryExecuted;
        /** null 表示已完成查询执行，路径可达性与安装状态由后续逻辑核验 */
        public final PathResult preflightFailure;
        public final float nativeRange, searchRange;
        public final Options options;
        public final Boolean navigable;
        public final boolean breakDoors, avoidWater;
        public final double speed;
        public final Point queryTarget, nativeTarget, endpoint;
        public final EndpointRelation endpointRelation;
        public final int pathLength;
        public final String diagnostic;

        private Creation(QueryContext context, Point queryTarget, Point nativeTarget, double speed, PathEntity path,
            boolean queryExecuted, PathResult preflightFailure, float nativeRange, float searchRange, Options options,
            Boolean navigable, boolean breakDoors, boolean avoidWater, String diagnostic) {
            this.context = context;
            this.path = path;
            this.queryExecuted = queryExecuted;
            this.preflightFailure = preflightFailure;
            this.nativeRange = nativeRange;
            this.searchRange = searchRange;
            this.options = options;
            this.navigable = navigable;
            this.breakDoors = breakDoors;
            this.avoidWater = avoidWater;
            this.speed = speed;
            this.queryTarget = queryTarget;
            this.nativeTarget = nativeTarget;
            var facts = queryExecuted ? PathFacts.inspect(path, nativeTarget) : null;
            endpoint = facts == null ? null : facts.endpoint;
            endpointRelation = facts == null ? EndpointRelation.NOT_QUERIED : facts.relation;
            pathLength = facts == null ? MaidAiTuning.NAVIGATION_INITIAL_VALUE : facts.length;
            this.diagnostic = diagnostic;
        }
    }

    public static final class Installation {

        public final PathResult result;
        public final transient PathEntity requestedPath, installedPath;
        /** started 仅指示原生 setPath 返回成功；上下文发生变更时自动放弃资源占用 */
        public final boolean nativeSetPathCalled, started, reusedExistingPath, contextChanged;
        public final String diagnostic;

        private Installation(PathResult result, PathEntity requestedPath, PathEntity installedPath,
            boolean nativeSetPathCalled, boolean started, boolean reusedExistingPath, boolean contextChanged,
            String diagnostic) {
            this.result = result;
            this.requestedPath = requestedPath;
            this.installedPath = installedPath;
            this.nativeSetPathCalled = nativeSetPathCalled;
            this.started = started;
            this.reusedExistingPath = reusedExistingPath;
            this.contextChanged = contextChanged;
            this.diagnostic = diagnostic;
        }
    }

    static final class PathFacts {

        final EndpointRelation relation;
        final Point endpoint;
        final int length;

        private PathFacts(EndpointRelation relation, Point endpoint, int length) {
            this.relation = relation;
            this.endpoint = endpoint;
            this.length = length;
        }

        static PathFacts inspect(PathEntity path, Point nativeTarget) {
            if (path == null)
                return new PathFacts(EndpointRelation.NULL_PATH, null, MaidAiTuning.NAVIGATION_INITIAL_VALUE);
            int length = path.getCurrentPathLength();
            if (length <= 0) return new PathFacts(EndpointRelation.EMPTY_PATH, null, length);
            var last = path.getFinalPathPoint();
            var endpoint = last == null ? null : new Point(last.xCoord, last.yCoord, last.zCoord);
            if (endpoint == null || nativeTarget == null)
                return new PathFacts(EndpointRelation.UNKNOWN_TARGET, endpoint, length);
            boolean same = endpoint.x == nativeTarget.x && endpoint.y == nativeTarget.y && endpoint.z == nativeTarget.z;
            return new PathFacts(
                same ? EndpointRelation.MATCHES_NATIVE_TARGET : EndpointRelation.DIFFERS_FROM_NATIVE_TARGET,
                endpoint,
                length);
        }
    }

    private static final class QueryContext {

        private final EntityMaid maid;
        private final UUID identity;
        private final World world;
        private final PathNavigate navigator;
        private final long navigationGeneration;
        private final String owner;
        private final MaidTask command;
        private final int entityTick;
        private final long worldTick;
        private final MaidPolicyRuntime runtime;
        private final Token token;

        private QueryContext(EntityMaid maid, PathNavigate navigator) {
            this.maid = maid;
            identity = maid.getUniqueID();
            world = maid.worldObj;
            this.navigator = navigator;
            navigationGeneration = navigator instanceof MaidPathNavigator modern ? modern.generation()
                : MaidAiTuning.NAVIGATION_INITIAL_VALUE;
            owner = maid.func_152113_b();
            command = maid.getTask();
            entityTick = maid.ticksExisted;
            worldTick = world == null ? MaidAiTuning.NAVIGATION_INITIAL_VALUE : world.getTotalWorldTime();
            runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            token = runtime == null ? null
                : runtime.getContext()
                    .getToken();
        }

        private boolean matches(EntityMaid candidate) {
            if (candidate != maid || !identity.equals(candidate.getUniqueID())
                || candidate.worldObj != world
                || candidate.isDead
                || !candidate.isEntityAlive()
                || candidate.getNavigator() != navigator
                || navigator instanceof MaidPathNavigator modern && modern.generation() != navigationGeneration
                || !Objects.equals(owner, candidate.func_152113_b())
                || candidate.getTask() != command
                || candidate.ticksExisted != entityTick
                || world == null
                || world.isRemote
                || MaidBrainLifecycle.isWorldUnloading(world)
                || world.getTotalWorldTime() != worldTick
                || candidate.getBrainCoordinator()
                    .isLifecycleFailed()
                || candidate.getBrainCoordinator()
                    .peekPolicyRuntime() != runtime)
                return false;
            return runtime != null && runtime.getContext()
                .isCurrent(token);
        }
    }

    /** 只读诊断；null navigable 表示预检已拒绝，尚未调用原生 gate */
    public static final class Attempt {

        public final PathResult result;
        public final float nativeRange, searchRange;
        public final Options options;
        public final Boolean navigable;
        public final boolean breakDoors, avoidWater;
        public final String diagnostic;

        private Attempt(PathResult result, float nativeRange, float searchRange, Options options, Boolean navigable,
            boolean breakDoors, boolean avoidWater, String diagnostic) {
            this.result = result;
            this.nativeRange = nativeRange;
            this.searchRange = searchRange;
            this.options = options;
            this.navigable = navigable;
            this.breakDoors = breakDoors;
            this.avoidWater = avoidWater;
            this.diagnostic = diagnostic;
        }
    }

    public static final class Options {

        public final boolean supported, canSwim, enterDoors;
        public final String diagnostic;

        private Options(boolean supported, boolean canSwim, boolean enterDoors, String diagnostic) {
            this.supported = supported;
            this.canSwim = canSwim;
            this.enterDoors = enterDoors;
            this.diagnostic = diagnostic;
        }
    }

    /** 成员只查找一次，失败也是一个可报告的适配结果，不在静态初始化中抛出 */
    static final class NativeAccess {

        private final Field canSwim, enterDoors;
        private final Method canNavigate;
        private final String failure;

        private NativeAccess(Field canSwim, Field enterDoors, Method canNavigate, String failure) {
            this.canSwim = canSwim;
            this.enterDoors = enterDoors;
            this.canNavigate = canNavigate;
            this.failure = failure;
        }

        static NativeAccess resolve(Class<?> definition) {
            try {
                var swim = ReflectionHelper
                    .findField(definition, MaidAiIds.ID_CANSWIM, MaidAiReflectionSpec.SRG_MEMBER_FIELD_75517_M);
                var doors = ReflectionHelper.findField(
                    definition,
                    MaidAiIds.ID_CANPASSOPENWOODENDOORS,
                    MaidAiReflectionSpec.SRG_MEMBER_FIELD_75518_J);
                var navigate = ReflectionHelper.findMethod(
                    definition,
                    null,
                    new String[] { MaidAiIds.ID_CANNAVIGATE, MaidAiReflectionSpec.SRG_MEMBER_FUNC_75485_K });
                if (swim.getType() != boolean.class || doors.getType() != boolean.class
                    || navigate.getReturnType() != boolean.class)
                    return new NativeAccess(null, null, null, MaidAiIds.ID_NATIVE_NAVIGATION_MEMBER_TYPE_MISMATCH);
                return new NativeAccess(swim, doors, navigate, null);
            } catch (RuntimeException | LinkageError failure) {
                return new NativeAccess(null, null, null, MaidAiIds.ID_NATIVE_NAVIGATION_MEMBERS_UNAVAILABLE + failure);
            }
        }

        Options options(Object navigator) {
            if (failure != null) return new Options(false, false, false, failure);
            try {
                return new Options(
                    true,
                    canSwim.getBoolean(navigator),
                    enterDoors.getBoolean(navigator),
                    MaidAiIds.ID_NATIVE_FLAGS_READ);
            } catch (IllegalAccessException | RuntimeException failure) {
                return new Options(false, false, false, MaidAiIds.ID_NATIVE_NAVIGATION_FLAGS_UNREADABLE + failure);
            }
        }

        boolean canNavigate(Object navigator) {
            if (failure != null) throw new IllegalStateException(failure);
            try {
                return (Boolean) canNavigate.invoke(navigator);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IllegalStateException(MaidAiIds.ID_NATIVE_CANNAVIGATE_UNAVAILABLE, failure);
            }
        }
    }
}
