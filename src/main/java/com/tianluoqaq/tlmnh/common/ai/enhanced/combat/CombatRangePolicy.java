package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.Objects;
import java.util.UUID;

/** Unified-combat target range and bounded search timing; world access remains with the observer. */
public final class CombatRangePolicy {

    private static final int PERIOD = 40, SCOUT_TICKS = 8, EVENT_TICKS = 20, ALERT_TICKS = 60;
    private static final double MAID_TARGET_LIMIT = 24;
    private static final double OWNER_HURT_BONUS = .20, MAID_HURT_BONUS = .10, CURRENT_BONUS = .015;
    private UUID selected, maidAttacker, ownerAttacker;
    private long lastTick = Long.MIN_VALUE, eventUntil = Long.MIN_VALUE, seenUntil = Long.MIN_VALUE;

    public static int normalizedAcquire(int acquire, int retain) {
        return Math.min(acquire, retain);
    }

    public void tick(long now, UUID maidThreat, UUID ownerThreat) {
        if (now < lastTick) clear();
        lastTick = now;
        if (maidThreat != null && !Objects.equals(maidThreat, maidAttacker)
            || ownerThreat != null && !Objects.equals(ownerThreat, ownerAttacker)) eventUntil = now + EVENT_TICKS;
        maidAttacker = maidThreat;
        ownerAttacker = ownerThreat;
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

    public void clear() {
        selected = maidAttacker = ownerAttacker = null;
        lastTick = eventUntil = seenUntil = Long.MIN_VALUE;
    }
}
