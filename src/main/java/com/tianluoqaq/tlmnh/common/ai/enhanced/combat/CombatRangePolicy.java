package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Unified-combat target range and bounded search timing; world access remains with the observer. */
public final class CombatRangePolicy {

    // 至少3次已提交近战未获原生接受，且首末实际worldTick跨满20tick完整无敌帧周期才判定无产出
    public static final int MIN_UNPRODUCTIVE_ATTEMPTS = 3, MIN_UNPRODUCTIVE_SPAN_TICKS = 20;
    // 超过两个最慢20tick近战周期未再发生拒绝则视作旧交战中断，不把历史零散挥空拼入新窗口
    public static final int STALE_REJECTION_GAP_TICKS = 40;
    // 与控制器RETRY_TICKS对齐，覆盖最小保持窗与跨格换靶接近行程
    public static final int UNPRODUCTIVE_COOLDOWN_TICKS = 100;
    // 默认2格近战触达加0.5格碰撞体站位余量，仅用于已建立观测的非主人友方温和避让
    public static final double FRIEND_MELEE_RADIUS_SQ = 6.25;
    private static final int PERIOD = 40, SCOUT_TICKS = 8, EVENT_TICKS = 20, ALERT_TICKS = 60;
    private static final int MAX_TRACKED_REJECTIONS = 32;
    private static final double MAID_TARGET_LIMIT = 24;
    private static final double OWNER_HURT_BONUS = .20, MAID_HURT_BONUS = .10, CURRENT_BONUS = .015;
    private static final double FRIEND_ENGAGED_PENALTY = .08, UNPRODUCTIVE_SCORE = 0;
    private final Map<UUID, Rejection> rejections = new LinkedHashMap<>();
    private UUID selected, maidAttacker, ownerAttacker;
    private long lastTick = Long.MIN_VALUE, eventUntil = Long.MIN_VALUE, seenUntil = Long.MIN_VALUE;

    public static int normalizedAcquire(int acquire, int retain) {
        return Math.min(acquire, retain);
    }

    public static int urgency(boolean ownerHurt, boolean maidHurt) {
        if (ownerHurt) return 2;
        return maidHurt ? 1 : 0;
    }

    public static boolean shouldYieldUnproductive(int candidateUrgency, boolean candidateUnproductive,
        int bestProductiveAlternativeUrgency) {
        return candidateUnproductive && bestProductiveAlternativeUrgency >= candidateUrgency;
    }

    public void tick(long now, UUID maidThreat, UUID ownerThreat) {
        if (now < lastTick) clear();
        lastTick = now;
        pruneRejections(now);
        if (maidThreat != null && !Objects.equals(maidThreat, maidAttacker)
            || ownerThreat != null && !Objects.equals(ownerThreat, ownerAttacker)) eventUntil = now + EVENT_TICKS;
        maidAttacker = maidThreat;
        ownerAttacker = ownerThreat;
    }

    /** 仅记录已实际提交冷却的近战结果；命中只解除对应目标身份的退让，不清空其他拥挤目标 */
    public void recordMeleeOutcome(UUID target, long now, boolean accepted) {
        if (target == null) return;
        if (now < lastTick) clear();
        lastTick = Math.max(lastTick, now);
        pruneRejections(now);
        if (accepted) {
            rejections.remove(target);
            return;
        }
        Rejection state = rejections.get(target);
        if (state == null) {
            if (rejections.size() >= MAX_TRACKED_REJECTIONS) rejections.remove(
                rejections.keySet()
                    .iterator()
                    .next());
            rejections.put(target, new Rejection(now));
            return;
        }
        if (state.count == 0 || now < state.lastRejectedAt
            || now - state.lastRejectedAt > STALE_REJECTION_GAP_TICKS) {
            state.firstRejectedAt = state.lastRejectedAt = now;
            state.count = 1;
            return;
        }
        state.lastRejectedAt = now;
        state.count++;
        if (state.count >= MIN_UNPRODUCTIVE_ATTEMPTS && now - state.firstRejectedAt >= MIN_UNPRODUCTIVE_SPAN_TICKS) {
            state.unproductiveUntil = now + UNPRODUCTIVE_COOLDOWN_TICKS;
            state.firstRejectedAt = now;
            state.count = 0;
        }
    }

    public boolean isMeleeUnproductive(UUID target, long now) {
        if (target == null) return false;
        if (now < lastTick) {
            clear();
            return false;
        }
        Rejection state = rejections.get(target);
        if (state == null) return false;
        if (now < state.unproductiveUntil) return true;
        if (state.stale(now)) rejections.remove(target);
        return false;
    }

    public void seen(long now) {
        seenUntil = now + ALERT_TICKS;
    }

    public void select(UUID id) {
        selected = id;
    }

    public void cancel() {
        selected = null;
    }

    public boolean retained(UUID id) {
        return id != null && id.equals(selected);
    }

    public boolean targetAllowed(UUID id, double ownerDistanceSq, double maidDistanceSq, int acquire, int retain) {
        double radius = retained(id) ? retain : acquire;
        return Double.isFinite(ownerDistanceSq) && Double.isFinite(maidDistanceSq)
            && ownerDistanceSq <= radius * radius
            && maidDistanceSq <= MAID_TARGET_LIMIT * MAID_TARGET_LIMIT;
    }

    public double detection(long now, UUID maid, UUID target, double base, int acquire, int retain) {
        double bounded = Math.min(MAID_TARGET_LIMIT, base);
        if (retained(target)) return Math.max(bounded, retain);
        long offset = Math.floorMod(maid.hashCode(), PERIOD);
        boolean scout = Math.floorMod(now + offset, PERIOD) < SCOUT_TICKS;
        return scout || now < eventUntil || now < seenUntil ? Math.max(bounded, acquire) : bounded;
    }

    public static double score(double distance, boolean ownerHurt, boolean maidHurt, boolean current) {
        return Math.min(
            1,
            1 / (1 + distance) + (ownerHurt ? OWNER_HURT_BONUS : 0)
                + (maidHurt ? MAID_HURT_BONUS : 0)
                + (current ? CURRENT_BONUS : 0));
    }

    public static double score(double distance, boolean ownerHurt, boolean maidHurt, boolean current,
        boolean friendEngaged, boolean demoteUnproductive) {
        if (demoteUnproductive) return UNPRODUCTIVE_SCORE;
        double base = score(distance, ownerHurt, maidHurt, current);
        if (friendEngaged && !ownerHurt && !maidHurt) return Math.max(0, base - FRIEND_ENGAGED_PENALTY);
        return base;
    }

    public void clear() {
        selected = maidAttacker = ownerAttacker = null;
        lastTick = eventUntil = seenUntil = Long.MIN_VALUE;
        rejections.clear();
    }

    private void pruneRejections(long now) {
        rejections.entrySet()
            .removeIf(
                entry -> entry.getValue()
                    .stale(now));
    }

    private static final class Rejection {

        long firstRejectedAt;
        long lastRejectedAt;
        long unproductiveUntil = Long.MIN_VALUE;
        int count = 1;

        Rejection(long now) {
            firstRejectedAt = lastRejectedAt = now;
        }

        boolean stale(long now) {
            return now >= unproductiveUntil && now - lastRejectedAt > STALE_REJECTION_GAP_TICKS;
        }
    }
}
