package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Objects;
import java.util.function.Function;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathCreation;

/** 增强移动独立管理重规划与失败冷却；不改变F的MoveToTargetSink合同 */
public final class EnhancedNavigationController {

    public enum Outcome {
        IDLE,
        ARRIVED,
        RUNNING,
        PARTIAL_PATH,
        WAITING_BUDGET,
        NOT_NAVIGABLE,
        UNREACHABLE,
        COOLDOWN,
        RECOVERY_WAIT
    }

    private static final int FAILURE_RETRY_TICKS = 20;
    private static final int NO_PROGRESS_TICKS = 40;
    private static final double REPLAN_DISTANCE_SQUARED = 4;
    private final FollowMoveActor actor;
    private final Function<BlockPos, MaidPathCreation> requests;
    private final Runnable cancelQueue;
    private BlockPos plannedTarget, failedTarget;
    private long retryAt, lastTick = Long.MIN_VALUE, waiting, failures, partialPaths;
    private BlockPos progressAnchor, partialOrigin;
    private long lastProgressAt, recoveries;
    private MaidPathCreation preflight;
    private BlockPos preflightTarget;
    private long preflightTick;
    private Outcome outcome = Outcome.IDLE;

    public EnhancedNavigationController(FollowMoveActor actor, Function<BlockPos, MaidPathCreation> requests,
        Runnable cancelQueue) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.cancelQueue = Objects.requireNonNull(cancelQueue, "cancelQueue");
    }

    public Outcome tick() {
        long now = actor.worldGameTime();
        var supplied = preflight;
        var suppliedTarget = preflightTarget;
        long suppliedTick = preflightTick;
        preflight = null;
        preflightTarget = null;
        if (now < lastTick) {
            retryAt = 0;
            progressAnchor = null;
            partialOrigin = null;
        }
        lastTick = now;
        var target = actor.getWalkTarget();
        if (target == null || target.isEntityTrackerSpectator()) {
            stopRoute();
            return outcome = Outcome.IDLE;
        }
        var position = target.currentBlockPosition();
        if (failedTarget != null && position.distanceSquared(failedTarget) > REPLAN_DISTANCE_SQUARED) {
            failedTarget = null;
            retryAt = 0;
        }
        if (position.manhattanDistance(actor.blockPosition()) <= target.getCloseEnoughDistance()) {
            stopRoute();
            actor.eraseCantReachSince();
            return outcome = Outcome.ARRIVED;
        }
        var installed = actor.navigationPath();
        boolean changed = plannedTarget == null || position.distanceSquared(plannedTarget) > REPLAN_DISTANCE_SQUARED;
        var current = actor.blockPosition();
        if (supplied != null && suppliedTick == now && position.distanceSquared(suppliedTarget) == 0)
            return applyCreation(supplied, target, position, current, now);
        if (changed || progressAnchor == null || current.distanceSquared(progressAnchor) >= 1) {
            progressAnchor = current;
            lastProgressAt = now;
        }
        if (!changed && installed != null
            && (actor.navigationIsStuck() || !actor.navigationIsDone() && now - lastProgressAt >= NO_PROGRESS_TICKS
                || actor.navigationIsDone() && !installed.canReach()
                    && partialOrigin != null
                    && current.distanceSquared(partialOrigin) < 1)) {
            recover(now, position);
            return outcome;
        }
        if (!changed && installed != null && !actor.navigationIsDone() && !actor.navigationIsStuck()) {
            // 水陆切换入口可能仍以同一身份等待；无新root查询不代表没有排队工作
            actor.setPathMemory(installed);
            return outcome = installed.canReach() ? Outcome.RUNNING : Outcome.PARTIAL_PATH;
        }
        if (now < retryAt) {
            return outcome = Outcome.COOLDOWN;
        }
        // 每次读取本tick实际目标；队列不保存路径请求或旧世界回调
        return applyCreation(requests.apply(position), target, position, current, now);
    }

    /** 只接同帧、同坐标的成功预检；实际安装仍由actor验证创建身份与授权 */
    void acceptPreflight(BlockPos target, MaidPathCreation created, long tick) {
        if (created.result != MaidPathCreation.Result.FOUND || !created.path.canReach())
            throw new IllegalArgumentException("Reachable preflight required");
        preflightTarget = Objects.requireNonNull(target, "target");
        preflight = created;
        preflightTick = tick;
    }

    private Outcome applyCreation(MaidPathCreation created, WalkTarget target, BlockPos position, BlockPos current,
        long now) {
        switch (created.result) {
            case WAITING_BUDGET -> {
                waiting++;
                return outcome = Outcome.WAITING_BUDGET;
            }
            case NOT_NAVIGABLE -> {
                return outcome = Outcome.NOT_NAVIGABLE;
            }
            case UNREACHABLE -> {
                failed(now, position);
                return outcome;
            }
            case FOUND -> {
                if (!actor.moveTo(created.path, target.getSpeedModifier())) {
                    failed(now, position);
                    return outcome;
                }
                var actual = actor.navigationPath();
                if (actual == null) throw new IllegalStateException("Navigation accepted no actual route");
                actor.setPathMemory(actual);
                plannedTarget = position;
                progressAnchor = current;
                lastProgressAt = now;
                partialOrigin = actual.canReach() ? null : current;
                failedTarget = null;
                retryAt = 0;
                if (actual.canReach()) {
                    actor.eraseCantReachSince();
                    return outcome = Outcome.RUNNING;
                }
                partialPaths++;
                if (actor.getCantReachSince() == null) actor.setCantReachSince(now);
                return outcome = Outcome.PARTIAL_PATH;
            }
        }
        throw new IllegalStateException("Unknown navigation creation result");
    }

    private void failed(long now, BlockPos target) {
        actor.stopNavigation();
        actor.setPathMemory(null);
        failures++;
        failedTarget = target;
        retryAt = now + FAILURE_RETRY_TICKS;
        if (actor.getCantReachSince() == null) actor.setCantReachSince(now);
        outcome = Outcome.UNREACHABLE;
    }

    private void recover(long now, BlockPos target) {
        actor.stopNavigation();
        actor.setPathMemory(null);
        failedTarget = target;
        partialOrigin = null;
        progressAnchor = null;
        retryAt = now + FAILURE_RETRY_TICKS;
        if (actor.getCantReachSince() == null) actor.setCantReachSince(now);
        recoveries++;
        outcome = Outcome.RECOVERY_WAIT;
    }

    private void stopRoute() {
        // 呼吸预检也可能尚无WALK_TARGET却已排队；只有显式动作取消才释放整个身份
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        plannedTarget = null;
        failedTarget = null;
        retryAt = 0;
        progressAnchor = partialOrigin = null;
        preflight = null;
        preflightTarget = null;
    }

    /** 退休时不使用失效actor，只释放构造时绑定的原世界/身份队列 */
    public void cancel() {
        cancelQueue.run();
        plannedTarget = null;
        failedTarget = null;
        retryAt = 0;
        progressAnchor = partialOrigin = null;
        lastTick = Long.MIN_VALUE;
        preflight = null;
        preflightTarget = null;
        outcome = Outcome.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    public long waitingCount() {
        return waiting;
    }

    public long failureCount() {
        return failures;
    }

    public long partialPathCount() {
        return partialPaths;
    }

    public long recoveryCount() {
        return recoveries;
    }
}
