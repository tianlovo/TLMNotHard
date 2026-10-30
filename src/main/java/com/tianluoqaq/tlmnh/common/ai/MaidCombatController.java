package com.tianluoqaq.tlmnh.common.ai;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;

/** 近战动作的有界生命周期；执行端提供感知、目标规则、原生导航与攻击 */
public final class MaidCombatController {

    public static final int SCAN_INTERVAL = 10;
    public static final int PATH_INTERVAL = 10;
    public static final int PATH_RETRY_INTERVAL = 40;
    public static final int ATTACK_INTERVAL = 20;
    public static final int HIDDEN_TIMEOUT = 40;
    public static final int PURSUIT_TIMEOUT = 200;
    private static final int STUCK_INTERVAL = 40;
    private static final int MAX_PATH_FAILURES = 3;

    public enum Status {
        IDLE,
        HARD_STOP,
        DISABLED,
        NO_OWNER,
        NO_WEAPON,
        RANGED_UNSUPPORTED,
        SEARCHING,
        NO_TARGET,
        TARGET_LOST,
        TARGET_HIDDEN,
        CHASING,
        PATH_COOLDOWN,
        PATH_FAILED,
        STUCK,
        ATTACKING,
        PREPARING_ATTACK,
        ATTACK_COOLDOWN,
        CANCELLED
    }

    /** claimControl 必须先取消旧动作，release 只能释放本动作拥有的目标和路径 */
    public interface Actor {

        Status blockedReason();

        boolean hasTarget();

        boolean acquireTarget();

        boolean targetValid();

        boolean targetVisible();

        double distanceSq();

        double reachSq();

        void claimControl();

        void lookAtTarget();

        boolean hasPath();

        PathResult approachTarget();

        void stopNavigation();

        boolean attack();

        default boolean prepareAttack() {
            return true;
        }

        default void interruptAttack() {}

        default int attackInterval() {
            return ATTACK_INTERVAL;
        }

        void release(boolean suppressTarget);

        double x();

        double y();

        double z();
    }

    private final Actor actor;
    private long tick, nextScan, nextPath, nextAttack, pursuitStarted, progressTick;
    private long scans, selections, pathRequests, pathFailures, attackAttempts, hits, cancellations;
    private int unseenTicks, consecutivePathFailures;
    private double progressX, progressY, progressZ;
    private boolean active;
    private Status status = Status.IDLE;
    private PathResult lastPathResult;

    public MaidCombatController(Actor actor, int phase) {
        this.actor = Objects.requireNonNull(actor, "actor");
        nextScan = Math.floorMod(phase, SCAN_INTERVAL) + 1L;
    }

    /** true 表示本 tick 占用动作，false 表示协调器可恢复主人跟随 */
    public boolean tick() {
        tick++;
        var blocked = actor.blockedReason();
        if (blocked != null) return stop(blocked, false);
        if (!actor.hasTarget()) {
            if (tick < nextScan) {
                status = Status.SEARCHING;
                return false;
            }
            scans++;
            nextScan = tick + SCAN_INTERVAL;
            if (!actor.acquireTarget()) {
                status = Status.NO_TARGET;
                return false;
            }
        }
        if (!actor.targetValid()) return stop(Status.TARGET_LOST, false);
        if (!active) {
            actor.claimControl();
            active = true;
            selections++;
            pursuitStarted = tick;
            unseenTicks = consecutivePathFailures = 0;
            markProgress();
        }
        boolean visible = actor.targetVisible();
        unseenTicks = visible ? 0 : unseenTicks + 1;
        if (unseenTicks > HIDDEN_TIMEOUT) return stop(Status.TARGET_HIDDEN, true);
        actor.lookAtTarget();
        double distance = actor.distanceSq();
        if (!Double.isFinite(distance)) return stop(Status.TARGET_LOST, false);
        if (visible && distance <= actor.reachSq()) {
            actor.stopNavigation();
            pursuitStarted = tick;
            markProgress();
            if (tick < nextAttack) {
                status = Status.ATTACK_COOLDOWN;
                return true;
            }
            if (!actor.prepareAttack()) {
                status = Status.PREPARING_ATTACK;
                return true;
            }
            nextAttack = tick + actor.attackInterval();
            attackAttempts++;
            if (actor.attack()) hits++;
            status = Status.ATTACKING;
            return true;
        }
        actor.interruptAttack();
        if (tick - pursuitStarted >= PURSUIT_TIMEOUT) return stop(Status.PATH_FAILED, true);
        if (tick - progressTick >= STUCK_INTERVAL) {
            double movement = square(actor.x() - progressX) + square(actor.y() - progressY)
                + square(actor.z() - progressZ);
            markProgress();
            if (actor.hasPath() && movement < 0.0625) {
                actor.stopNavigation();
                pathFailures++;
                consecutivePathFailures++;
                nextPath = Math.max(nextPath, tick + PATH_RETRY_INTERVAL);
                if (consecutivePathFailures >= MAX_PATH_FAILURES) return stop(Status.PATH_FAILED, true);
                status = Status.STUCK;
                return true;
            }
        }
        if (tick < nextPath) {
            status = actor.hasPath() ? Status.CHASING : Status.PATH_COOLDOWN;
            return true;
        }
        pathRequests++;
        nextPath = tick + PATH_INTERVAL;
        lastPathResult = actor.approachTarget();
        if (lastPathResult == PathResult.STARTED) {
            consecutivePathFailures = 0;
            status = Status.CHASING;
        } else {
            pathFailures++;
            consecutivePathFailures++;
            nextPath = tick + PATH_RETRY_INTERVAL;
            actor.stopNavigation();
            if (consecutivePathFailures >= MAX_PATH_FAILURES) return stop(Status.PATH_FAILED, true);
            status = Status.PATH_FAILED;
        }
        return true;
    }

    public void cancel() {
        stop(Status.CANCELLED, false);
    }

    private boolean stop(Status reason, boolean suppressTarget) {
        actor.interruptAttack();
        if (active || actor.hasTarget()) {
            actor.release(suppressTarget);
            if (active) cancellations++;
        }
        active = false;
        status = reason;
        return false;
    }

    private void markProgress() {
        progressX = actor.x();
        progressY = actor.y();
        progressZ = actor.z();
        progressTick = tick;
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

    public long getScans() {
        return scans;
    }

    public long getSelections() {
        return selections;
    }

    public long getPathRequests() {
        return pathRequests;
    }

    public long getPathFailures() {
        return pathFailures;
    }

    public long getAttackAttempts() {
        return attackAttempts;
    }

    public long getHits() {
        return hits;
    }

    public long getCancellations() {
        return cancellations;
    }
}
