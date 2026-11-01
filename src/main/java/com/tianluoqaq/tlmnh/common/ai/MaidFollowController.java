package com.tianluoqaq.tlmnh.common.ai;

import java.util.Objects;

/** 单只女仆的跟随决策，执行端负责验证主人和使用实际导航器 */
public final class MaidFollowController {

    public static final int OWNER_SCAN_INTERVAL = 10;
    public static final int PATH_INTERVAL = 10;
    public static final int FAILURE_RETRY_INTERVAL = 40;
    public static final int STUCK_INTERVAL = 40;
    public static final double START_DISTANCE_SQ = 25;
    public static final double STOP_DISTANCE_SQ = 9;
    public static final double MAX_DISTANCE_SQ = 4096;
    private static final double PROGRESS_DISTANCE_SQ = 0.0625;
    private static final double MOVED_TARGET_DISTANCE_SQ = 4;

    public enum Status {
        IDLE,
        FOLLOWING,
        CLOSE_TO_OWNER,
        OWNER_UNAVAILABLE,
        OWNER_TOO_FAR,
        PATH_COOLDOWN,
        PATH_UNAVAILABLE,
        DESTINATION_UNLOADED,
        NOT_NAVIGABLE,
        ADAPTER_UNSUPPORTED,
        NAVIGATION_PENDING,
        STUCK,
        SITTING,
        RIDING,
        SLEEPING,
        LEASHED,
        DEAD,
        UNTAMED,
        CLIENT,
        BUSY,
        SUSPENDED,
        CANCELLED
    }

    public enum PathResult {

        STARTED,
        UNREACHABLE,
        UNLOADED,
        NOT_NAVIGABLE,
        ADAPTER_UNSUPPORTED,
        WAITING_BUDGET,
        STALE_REQUEST;

        public boolean isDeferred() {
            return this == WAITING_BUDGET || this == STALE_REQUEST;
        }
    }

    /** 所有调用都来自实体所属世界的服务端线程，不在后台访问世界 */
    public interface Actor {

        /** null 表示允许跟随；坐下等硬约束必须每 tick 读取 */
        Status stopReason();

        boolean refreshOwner();

        void forgetOwner();

        boolean ownerValid();

        double distanceToOwnerSq();

        double ownerX();

        double ownerY();

        double ownerZ();

        double x();

        double y();

        double z();

        void lookAtOwner();

        boolean hasPath();

        PathResult requestPath();

        void stopNavigation();

        default boolean claimControl() {
            return true;
        }

        default boolean controlValid() {
            return true;
        }

        default void releaseControl() {}

        default void revokeControl() {}
    }

    private final Actor actor;
    private long tick, nextOwnerScan, nextPathRequest, failureUntil, progressTick;
    private long ownerScans, pathRequests, pathFailures, cancellations, stuckRecoveries;
    private double progressX, progressY, progressZ, failedX, failedY, failedZ;
    private boolean following, stopping;
    private Status status = Status.IDLE;
    private PathResult lastPathResult;

    public MaidFollowController(Actor actor, int phase) {
        this.actor = Objects.requireNonNull(actor, "actor");
        nextOwnerScan = Math.floorMod(phase, OWNER_SCAN_INTERVAL) + 1L;
    }

    public void tick() {
        if (stopping) return;
        tick++;
        var stopped = actor.stopReason();
        if (stopped != null) {
            stop(stopped);
            return;
        }
        if (tick >= nextOwnerScan) {
            ownerScans++;
            actor.refreshOwner();
            nextOwnerScan = tick + OWNER_SCAN_INTERVAL;
        }
        if (!actor.ownerValid()) {
            stop(Status.OWNER_UNAVAILABLE);
            return;
        }
        double distance = actor.distanceToOwnerSq();
        if (!Double.isFinite(distance) || distance > MAX_DISTANCE_SQ) {
            stop(Status.OWNER_TOO_FAR);
            return;
        }
        if (distance <= STOP_DISTANCE_SQ || !following && distance < START_DISTANCE_SQ) {
            stop(Status.CLOSE_TO_OWNER);
            return;
        }
        if (!following) {
            if (!actor.claimControl() || !actor.controlValid() || !actor.ownerValid()) {
                stop(Status.CANCELLED);
                return;
            }
            following = true;
            markProgress();
        }
        if (!actor.controlValid()) {
            stop(Status.CANCELLED);
            return;
        }
        actor.lookAtOwner();
        if (tick - progressTick >= STUCK_INTERVAL) {
            double moved = square(actor.x() - progressX) + square(actor.y() - progressY)
                + square(actor.z() - progressZ);
            if (moved < PROGRESS_DISTANCE_SQ && actor.hasPath()) {
                actor.stopNavigation();
                stuckRecoveries++;
                rememberFailure();
                markProgress();
                status = Status.STUCK;
                return;
            }
            markProgress();
        }
        if (tick < nextPathRequest || tick < failureUntil && !failedTargetMoved()) {
            status = actor.hasPath() ? Status.FOLLOWING : Status.PATH_COOLDOWN;
            return;
        }
        nextPathRequest = tick + PATH_INTERVAL;
        var result = actor.requestPath();
        lastPathResult = result;
        if (result.isDeferred()) {
            nextPathRequest = tick + 1;
            markProgress();
            status = Status.NAVIGATION_PENDING;
            return;
        }
        pathRequests++;
        if (result == PathResult.STARTED) {
            failureUntil = 0;
            status = Status.FOLLOWING;
        } else {
            pathFailures++;
            rememberFailure();
            actor.stopNavigation();
            if (result == PathResult.UNLOADED) status = Status.DESTINATION_UNLOADED;
            else if (result == PathResult.NOT_NAVIGABLE) status = Status.NOT_NAVIGABLE;
            else if (result == PathResult.ADAPTER_UNSUPPORTED) status = Status.ADAPTER_UNSUPPORTED;
            else status = Status.PATH_UNAVAILABLE;
        }
    }

    /** 死亡、卸载或调度停止后不保留路径、主人引用的使用权或进行中的动作 */
    public void cancel() {
        stop(Status.CANCELLED);
        actor.forgetOwner();
        failureUntil = 0;
    }

    private void stop(Status reason) {
        if (stopping) return;
        stopping = true;
        try {
            actor.revokeControl();
            if (following || actor.hasPath() || status != reason) {
                actor.stopNavigation();
                if (following) cancellations++;
            }
        } finally {
            actor.releaseControl();
            following = false;
            failureUntil = 0;
            status = reason;
            stopping = false;
        }
    }

    private void markProgress() {
        progressX = actor.x();
        progressY = actor.y();
        progressZ = actor.z();
        progressTick = tick;
    }

    private void rememberFailure() {
        failureUntil = tick + FAILURE_RETRY_INTERVAL;
        failedX = actor.ownerX();
        failedY = actor.ownerY();
        failedZ = actor.ownerZ();
    }

    private boolean failedTargetMoved() {
        return square(actor.ownerX() - failedX) + square(actor.ownerY() - failedY) + square(actor.ownerZ() - failedZ)
            >= MOVED_TARGET_DISTANCE_SQ;
    }

    private static double square(double value) {
        return value * value;
    }

    public Status getStatus() {
        return status;
    }

    public PathResult getLastPathResult() {
        return lastPathResult;
    }

    public long getTicks() {
        return tick;
    }

    public long getOwnerScans() {
        return ownerScans;
    }

    public long getPathRequests() {
        return pathRequests;
    }

    public long getPathFailures() {
        return pathFailures;
    }

    public long getCancellations() {
        return cancellations;
    }

    public long getStuckRecoveries() {
        return stuckRecoveries;
    }
}
