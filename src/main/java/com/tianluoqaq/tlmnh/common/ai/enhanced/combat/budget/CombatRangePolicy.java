package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Unified-combat target range and bounded search timing; world access remains with the observer. */
public final class CombatRangePolicy {

    // 至少3次已提交近战未获原生接受，且首末实际worldTick跨满20tick完整无敌帧周期才判定无产出

    // 基础40tick覆盖常规节拍短暂走位重接，speed<1慢速节拍按两倍实际提交冷却放宽中断上界

    // 与控制器RETRY_TICKS对齐，覆盖最小保持窗与跨格换靶接近行程

    // 默认2格近战触达加0.5格碰撞体站位余量，仅用于新鲜观测的非主人友方近战争抢避让

    private final Map<UUID, Rejection> rejections = new LinkedHashMap<>();
    private UUID selected, maidAttacker, ownerAttacker;
    private long lastTick = Long.MIN_VALUE, eventUntil = Long.MIN_VALUE, seenUntil = Long.MIN_VALUE;

    public static int normalizedAcquire(int acquire, int retain) {
        return Math.min(acquire, retain);
    }

    public static int urgency(boolean ownerHurt, boolean maidHurt) {
        if (ownerHurt) return CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM;
        return maidHurt ? CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP
            : CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED;
    }

    public static boolean shouldYieldUnproductive(int candidateUrgency, boolean candidateUnproductive,
        int bestProductiveAlternativeUrgency) {
        return candidateUnproductive && bestProductiveAlternativeUrgency >= candidateUrgency;
    }

    public static long staleRejectionGapTicks(long committedCooldownTicks) {
        long cooldown = Math.max(CombatBudgetTuning.COMBAT_RANGE_MAX_FIRST_LIMIT, committedCooldownTicks);
        long twoCycles = cooldown > Long.MAX_VALUE / CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM ? Long.MAX_VALUE
            : cooldown * CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM;
        return Math.max(CombatBudgetTuning.MIN_STALE_REJECTION_GAP_TICKS, twoCycles);
    }

    public void tick(long now, UUID maidThreat, UUID ownerThreat) {
        if (now < lastTick) clear();
        lastTick = now;
        pruneRejections(now);
        if (maidThreat != null && !Objects.equals(maidThreat, maidAttacker)
            || ownerThreat != null && !Objects.equals(ownerThreat, ownerAttacker))
            eventUntil = now + CombatBudgetTuning.EVENT_TICKS;
        maidAttacker = maidThreat;
        ownerAttacker = ownerThreat;
    }

    /** 仅记录已实际提交冷却的近战结果；命中只解除对应目标身份的退让，不清空其他拥挤目标 */
    public void recordMeleeOutcome(UUID target, long now, boolean accepted, long committedCooldownTicks) {
        if (target == null) return;
        if (now < lastTick) clear();
        lastTick = Math.max(lastTick, now);
        pruneRejections(now);
        if (accepted) {
            rejections.remove(target);
            return;
        }
        long gapLimit = staleRejectionGapTicks(committedCooldownTicks);
        Rejection state = rejections.get(target);
        if (state == null) {
            if (rejections.size() >= CombatBudgetTuning.MAX_TRACKED_REJECTIONS) rejections.remove(
                rejections.keySet()
                    .iterator()
                    .next());
            rejections.put(target, new Rejection(now, gapLimit));
            return;
        }
        long allowedGap = Math.max(state.maxGapTicks, gapLimit);
        if (state.count == 0 || now < state.lastRejectedAt || now - state.lastRejectedAt > allowedGap) {
            state.firstRejectedAt = state.lastRejectedAt = now;
            state.maxGapTicks = gapLimit;
            state.count = CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP;
            return;
        }
        state.lastRejectedAt = now;
        state.maxGapTicks = allowedGap;
        state.count++;
        if (state.count >= CombatBudgetTuning.MIN_UNPRODUCTIVE_ATTEMPTS
            && now - state.firstRejectedAt >= CombatBudgetTuning.MIN_UNPRODUCTIVE_SPAN_TICKS) {
            long cooldownWindow = Math.max(
                CombatBudgetTuning.UNPRODUCTIVE_COOLDOWN_TICKS,
                Math.max(CombatBudgetTuning.COMBAT_RANGE_MAX_FIRST_LIMIT, committedCooldownTicks));
            state.unproductiveUntil = now + cooldownWindow;
            state.firstRejectedAt = now;
            state.count = CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED;
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
        seenUntil = now + CombatBudgetTuning.ALERT_TICKS;
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
            && maidDistanceSq <= CombatBudgetTuning.MAID_TARGET_LIMIT * CombatBudgetTuning.MAID_TARGET_LIMIT;
    }

    public double detection(long now, UUID maid, UUID target, double base, int acquire, int retain) {
        double bounded = Math.min(CombatBudgetTuning.MAID_TARGET_LIMIT, base);
        if (retained(target)) return Math.max(bounded, retain);
        long offset = Math.floorMod(maid.hashCode(), CombatBudgetTuning.PERIOD);
        boolean scout = Math.floorMod(now + offset, CombatBudgetTuning.PERIOD) < CombatBudgetTuning.SCOUT_TICKS;
        return scout || now < eventUntil || now < seenUntil ? Math.max(bounded, acquire) : bounded;
    }

    public static double score(double distance, boolean ownerHurt, boolean maidHurt, boolean current) {
        return Math.min(
            CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP,
            CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP
                / (CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP + distance)
                + (ownerHurt ? CombatBudgetTuning.OWNER_HURT_BONUS : CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED)
                + (maidHurt ? CombatBudgetTuning.MAID_HURT_BONUS : CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED)
                + (current ? CombatBudgetTuning.CURRENT_BONUS : CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED));
    }

    public static double score(double distance, boolean ownerHurt, boolean maidHurt, boolean current,
        boolean friendEngaged) {
        double base = score(distance, ownerHurt, maidHurt, current);
        if (friendEngaged && !ownerHurt && !maidHurt) return Math
            .max(CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED, base - CombatBudgetTuning.FRIEND_ENGAGED_PENALTY);
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
        long maxGapTicks;
        long unproductiveUntil = Long.MIN_VALUE;
        int count = CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP;

        Rejection(long now, long maxGapTicks) {
            firstRejectedAt = lastRejectedAt = now;
            this.maxGapTicks = maxGapTicks;
        }

        boolean stale(long now) {
            return now >= unproductiveUntil && now - lastRejectedAt > maxGapTicks;
        }
    }
}
