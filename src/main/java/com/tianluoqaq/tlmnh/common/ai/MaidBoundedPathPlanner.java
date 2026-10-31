package com.tianluoqaq.tlmnh.common.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.Kind;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** 限制单次原版路径查询范围，不改变实体属性或原导航器的路径执行 */
public final class MaidBoundedPathPlanner {

    private static final float MAX_SEARCH_RANGE = 16;
    private static final NativeAccess NATIVE = NativeAccess.resolve(PathNavigate.class);
    private Attempt lastAttempt;

    public PathResult request(EntityMaid maid, double x, double y, double z, double speed) {
        var result = performRequest(maid, x, y, z, speed);
        maid.getBrainCoordinator()
            .getTelemetry()
            .navigation(Kind.NAV_PROCESSED, "navigation", "", "planner_adapter_finalized:" + result, 0);
        return result;
    }

    private PathResult performRequest(EntityMaid maid, double x, double y, double z, double speed) {
        var navigator = maid.getNavigator();
        float nativeRange = navigator.getPathSearchRange();
        float searchRange = Math.min(nativeRange, MAX_SEARCH_RANGE);
        var options = NATIVE.options(navigator);
        boolean breakDoors = navigator.getCanBreakDoors(), avoidWater = navigator.getAvoidsWater();
        if (!options.supported) return finish(
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
            return finish(
                PathResult.UNREACHABLE,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                "invalid_search_parameters");
        }
        int padding = (int) (searchRange + 8.0F);
        int originX = MathHelper.floor_double(maid.posX), originZ = MathHelper.floor_double(maid.posZ);
        var chunks = maid.worldObj.getChunkProvider();
        for (int chunkX = (originX - padding) >> 4; chunkX <= (originX + padding) >> 4; chunkX++) {
            for (int chunkZ = (originZ - padding) >> 4; chunkZ <= (originZ + padding) >> 4; chunkZ++) {
                if (!chunks.chunkExists(chunkX, chunkZ)) return finish(
                    PathResult.UNLOADED,
                    nativeRange,
                    searchRange,
                    options,
                    null,
                    breakDoors,
                    avoidWater,
                    "cache_chunk_unloaded:" + chunkX + "," + chunkZ);
            }
        }
        if (!maid.worldObj
            .blockExists(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z))) {
            return finish(
                PathResult.UNLOADED,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                "destination_unloaded");
        }
        final boolean canNavigate;
        try {
            canNavigate = NATIVE.canNavigate(navigator);
        } catch (RuntimeException failure) {
            return finish(
                PathResult.ADAPTER_UNSUPPORTED,
                nativeRange,
                searchRange,
                options,
                null,
                breakDoors,
                avoidWater,
                failure.toString());
        }
        if (!canNavigate) return finish(
            PathResult.NOT_NAVIGABLE,
            nativeRange,
            searchRange,
            options,
            false,
            breakDoors,
            avoidWater,
            "native_canNavigate=false");
        var telemetry = maid.getBrainCoordinator()
            .getTelemetry();
        boolean recording = telemetry.isEnabled();
        long elapsed;
        net.minecraft.pathfinding.PathEntity path;
        telemetry.navigation(Kind.NAV_SUBMITTED, "navigation", "", "native_world_path_query", 0);
        long startedAt = recording ? System.nanoTime() : 0;
        try {
            path = maid.worldObj.getEntityPathToXYZ(
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
                "navigation",
                "",
                "native_path_threw:" + failure.getClass()
                    .getSimpleName());
            throw failure;
        } finally {
            elapsed = recording ? System.nanoTime() - startedAt : 0;
            if (recording) telemetry.work("native_path", WorkComponent.NATIVE_PATH, elapsed);
        }
        telemetry.navigation(Kind.NAV_RETURNED, "navigation", "", "path_null=" + (path == null), elapsed);
        boolean started = navigator.setPath(path, speed);
        return finish(
            started ? PathResult.STARTED : PathResult.UNREACHABLE,
            nativeRange,
            searchRange,
            options,
            true,
            breakDoors,
            avoidWater,
            path == null ? "native_path=null" : "native_setPath=" + started + ";length=" + path.getCurrentPathLength());
    }

    private PathResult finish(PathResult result, float nativeRange, float searchRange, Options options,
        Boolean navigable, boolean breakDoors, boolean avoidWater, String diagnostic) {
        lastAttempt = new Attempt(
            result,
            nativeRange,
            searchRange,
            options,
            navigable,
            breakDoors,
            avoidWater,
            diagnostic);
        return result;
    }

    public Attempt getLastAttempt() {
        return lastAttempt;
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
                var swim = ReflectionHelper.findField(definition, "canSwim", "field_75517_m");
                var doors = ReflectionHelper.findField(definition, "canPassOpenWoodenDoors", "field_75518_j");
                var navigate = ReflectionHelper
                    .findMethod(definition, null, new String[] { "canNavigate", "func_75485_k" });
                if (swim.getType() != boolean.class || doors.getType() != boolean.class
                    || navigate.getReturnType() != boolean.class)
                    return new NativeAccess(null, null, null, "native_navigation_member_type_mismatch");
                return new NativeAccess(swim, doors, navigate, null);
            } catch (RuntimeException | LinkageError failure) {
                return new NativeAccess(null, null, null, "native_navigation_members_unavailable:" + failure);
            }
        }

        Options options(Object navigator) {
            if (failure != null) return new Options(false, false, false, failure);
            try {
                return new Options(
                    true,
                    canSwim.getBoolean(navigator),
                    enterDoors.getBoolean(navigator),
                    "native_flags_read");
            } catch (IllegalAccessException | RuntimeException failure) {
                return new Options(false, false, false, "native_navigation_flags_unreadable:" + failure);
            }
        }

        boolean canNavigate(Object navigator) {
            if (failure != null) throw new IllegalStateException(failure);
            try {
                return (Boolean) canNavigate.invoke(navigator);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IllegalStateException("native_canNavigate_unavailable", failure);
            }
        }
    }
}
