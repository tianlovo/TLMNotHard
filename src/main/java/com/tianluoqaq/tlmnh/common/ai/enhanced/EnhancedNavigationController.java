package com.tianluoqaq.tlmnh.common.ai.enhanced;

import java.util.Objects;
import java.util.function.Function;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
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
        COOLDOWN
    }

    private static final int FAILURE_RETRY_TICKS = 20;
    private static final double REPLAN_DISTANCE_SQUARED = 4;
    private final FollowMoveActor actor;
    private final Function<BlockPos, MaidPathCreation> requests;
    private final Runnable cancelQueue;
    private BlockPos plannedTarget, failedTarget;
    private long retryAt, lastTick = Long.MIN_VALUE, waiting, failures, partialPaths;
    private Outcome outcome = Outcome.IDLE;

    public EnhancedNavigationController(FollowMoveActor actor, Function<BlockPos, MaidPathCreation> requests,
        Runnable cancelQueue) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.cancelQueue = Objects.requireNonNull(cancelQueue, "cancelQueue");
    }

    public Outcome tick() {
        long now = actor.worldGameTime();
        if (now < lastTick) retryAt = 0;
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
        if (!changed && installed != null && !actor.navigationIsDone() && !actor.navigationIsStuck()) {
            // 水陆切换入口可能仍以同一身份等待；无新root查询不代表没有排队工作
            actor.setPathMemory(installed);
            return outcome = installed.canReach() ? Outcome.RUNNING : Outcome.PARTIAL_PATH;
        }
        if (now < retryAt) {
            return outcome = Outcome.COOLDOWN;
        }
        // 每次读取本tick实际目标；队列不保存路径请求或旧世界回调
        var created = requests.apply(position);
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
        failures++;
        failedTarget = target;
        retryAt = now + FAILURE_RETRY_TICKS;
        if (actor.getCantReachSince() == null) actor.setCantReachSince(now);
        outcome = Outcome.UNREACHABLE;
    }

    private void stopRoute() {
        // 呼吸预检也可能尚无WALK_TARGET却已排队；只有显式动作取消才释放整个身份
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        plannedTarget = null;
        failedTarget = null;
        retryAt = 0;
    }

    /** 退休时不使用失效actor，只释放构造时绑定的原世界/身份队列 */
    public void cancel() {
        cancelQueue.run();
        plannedTarget = null;
        failedTarget = null;
        retryAt = 0;
        lastTick = Long.MIN_VALUE;
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
}
