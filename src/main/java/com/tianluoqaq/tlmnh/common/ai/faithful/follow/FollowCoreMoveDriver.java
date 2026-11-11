package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Path;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveState.Status;

/**
 * 按MC1.20.1 mapped MoveToTargetSink和Behavior字节码核对的独立移动组件
 * 仅管理条件、记忆、状态与调用顺序；不执行原生导航tick，不包含完整Brain或自然fallback实现
 */
public final class FollowCoreMoveDriver {

    private final int minDuration;
    private final int maxDuration;

    public FollowCoreMoveDriver() {
        this(
            FaithfulFollowTuning.FOLLOW_CORE_FOLLOW_CORE_MOVE_DRIVER_PARAM,
            FaithfulFollowTuning.FOLLOW_CORE_FOLLOW_CORE_MOVE_DRIVER_PARAM_2);
    }

    public FollowCoreMoveDriver(int minDuration, int maxDuration) {
        if (minDuration < 0 || maxDuration < minDuration || (long) maxDuration - minDuration + 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_INVALID_MOVE_BEHAVIOR_DURATION_RANGE);
        }
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    /** 开始pass只调用一次；外层仅对STOPPED sink调用，本方法不运行Follow或导航 */
    public boolean tryStart(FollowMoveActor actor, FollowMoveState state, long behaviorTime) {
        if (!hasRequiredMemories(actor) || !checkExtraStartConditions(actor, state)) {
            return false;
        }
        state.status = Status.RUNNING;
        int duration = minDuration + worldInt(actor, maxDuration + 1 - minDuration);
        state.endTimestamp = behaviorTime + duration;
        start(actor, state);
        return true;
    }

    /** 运行pass读取同tick最新记忆；外层仅对RUNNING sink调用 */
    public void tickOrStop(FollowMoveActor actor, FollowMoveState state, long behaviorTime) {
        if (behaviorTime > state.endTimestamp || !canStillUse(actor, state)) {
            doStop(actor, state, behaviorTime);
        } else {
            tick(actor, state);
        }
    }

    /** 保留原Behavior先STOPPED再执行sink.stop的次序 */
    public void doStop(FollowMoveActor actor, FollowMoveState state, long behaviorTime) {
        state.status = Status.STOPPED;
        WalkTarget target = actor.getWalkTarget();
        if (target != null && !reachedTarget(actor, target) && actor.navigationIsStuck()) {
            state.remainingCooldown = worldInt(actor, FaithfulFollowTuning.FOLLOW_CORE_WORLD_INT_SECOND_PARAM);
        }
        actor.stopNavigation();
        actor.eraseWalkTarget();
        actor.setPathMemory(null);
        state.path = null;
    }

    private boolean hasRequiredMemories(FollowMoveActor actor) {
        return actor.isCantReachSinceRegistered() && actor.isPathRegistered()
            && actor.getPathMemory() == null
            && actor.isWalkTargetRegistered()
            && actor.getWalkTarget() != null;
    }

    private boolean checkExtraStartConditions(FollowMoveActor actor, FollowMoveState state) {
        if (state.remainingCooldown > 0) {
            state.remainingCooldown--;
            return false;
        }
        WalkTarget target = actor.getWalkTarget();
        boolean reached = reachedTarget(actor, target);
        if (!reached && tryComputePath(actor, state, target, actor.worldGameTime())) {
            state.lastTargetPos = target.currentBlockPosition();
            return true;
        }
        actor.eraseWalkTarget();
        if (reached) {
            actor.eraseCantReachSince();
        }
        return false;
    }

    private boolean canStillUse(FollowMoveActor actor, FollowMoveState state) {
        if (state.path == null || state.lastTargetPos == null) {
            return false;
        }
        WalkTarget target = actor.getWalkTarget();
        boolean spectator = target != null && target.isEntityTrackerSpectator();
        return !actor.navigationIsDone() && target != null && !reachedTarget(actor, target) && !spectator;
    }

    private void start(FollowMoveActor actor, FollowMoveState state) {
        actor.setPathMemory(state.path);
        // 原方法忽略moveTo返回，false不代表这里自动stop或任务完成
        actor.moveTo(state.path, (double) state.speedModifier);
    }

    private void tick(FollowMoveActor actor, FollowMoveState state) {
        Path navigationPath = actor.navigationPath();
        if (!samePath(state.path, navigationPath)) {
            state.path = navigationPath;
            actor.setPathMemory(navigationPath);
        }
        if (navigationPath == null || state.lastTargetPos == null) {
            return;
        }
        WalkTarget target = actor.getWalkTarget();
        if (target.currentBlockPosition()
            .distanceSquared(state.lastTargetPos) > FaithfulFollowTuning.FOLLOW_CORE_TICK_PARAM
            && tryComputePath(actor, state, target, actor.worldGameTime())) {
            state.lastTargetPos = target.currentBlockPosition();
            start(actor, state);
        }
    }

    private boolean tryComputePath(FollowMoveActor actor, FollowMoveState state, WalkTarget target, long gameTime) {
        var targetPos = target.currentBlockPosition();
        state.path = actor.createPath(targetPos, FaithfulFollowTuning.DRY_PLATFORM_BEGIN_FRAME_ZERO_BASE);
        state.speedModifier = target.getSpeedModifier();
        if (reachedTarget(actor, target)) {
            actor.eraseCantReachSince();
            return false;
        }
        if (state.path != null && state.path.canReach()) {
            actor.eraseCantReachSince();
        } else if (actor.getCantReachSince() == null) {
            actor.setCantReachSince(gameTime);
        }
        if (state.path != null) {
            return true;
        }
        Vec3 fallback = actor.findFallbackTowards(
            FaithfulFollowTuning.TARGET_HORIZONTAL_RANGE_THRESHOLD,
            FaithfulFollowTuning.TARGET_VERTICAL_RANGE_THRESHOLD,
            new Vec3(
                targetPos.x + FaithfulFollowTuning.CENTER_OFFSET_D_2,
                targetPos.y,
                targetPos.z + FaithfulFollowTuning.CENTER_OFFSET_D_2),
            FaithfulFollowTuning.FALLBACK_ANGLE);
        if (fallback != null) {
            state.path = actor.createPath(fallback, FaithfulFollowTuning.DRY_PLATFORM_BEGIN_FRAME_ZERO_BASE);
            return state.path != null;
        }
        return false;
    }

    private static boolean reachedTarget(FollowMoveActor actor, WalkTarget target) {
        return target.currentBlockPosition()
            .manhattanDistance(actor.blockPosition()) <= target.getCloseEnoughDistance();
    }

    private static boolean samePath(Path left, Path right) {
        return left == right || left != null && right != null && left.getIdentity() == right.getIdentity();
    }

    private static int worldInt(FollowMoveActor actor, int bound) {
        int value = actor.nextWorldInt(bound);
        if (value < 0 || value >= bound) {
            throw new IllegalArgumentException(FaithfulFollowDiagnostics.MSG_WORLD_RANDOM_RESULT_OUTSIDE_REQUESTED);
        }
        return value;
    }
}
