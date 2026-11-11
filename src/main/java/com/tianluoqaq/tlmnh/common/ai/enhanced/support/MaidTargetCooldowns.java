package com.tianluoqaq.tlmnh.common.ai.enhanced.support;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/** 短期不可达候选冷却表，仅收敛接近失败退避，不介入攻击节拍或命中争抢 */
public final class MaidTargetCooldowns<K> {

    public enum EvictionPolicy {
        FIFO_OLDEST,
        CLEAR_ON_FULL
    }

    public static final class Entry {

        private final long retryAt;
        private final double x;
        private final double y;
        private final double z;
        private final Object contextRef;

        private Entry(long retryAt, double x, double y, double z, Object contextRef) {
            this.retryAt = retryAt;
            this.x = x;
            this.y = y;
            this.z = z;
            this.contextRef = contextRef;
        }

        public long retryAt() {
            return retryAt;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public Object contextRef() {
            return contextRef;
        }

        public double distanceSq(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<K, Entry> entries;
    private final int maxEntries;
    private final EvictionPolicy evictionPolicy;
    private long lastTick = Long.MIN_VALUE;

    private MaidTargetCooldowns(Map<K, Entry> entries, int maxEntries, EvictionPolicy evictionPolicy) {
        if (maxEntries <= 0)
            throw new IllegalArgumentException(EnhancedSupportDiagnostics.MSG_MAXENTRIES_MUST_BE_POSITIVE);
        this.entries = entries;
        this.maxEntries = maxEntries;
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, EnhancedSupportIds.ID_EVICTIONPOLICY);
    }

    public static <K> MaidTargetCooldowns<K> forUuidFifo(int maxEntries) {
        return new MaidTargetCooldowns<>(new LinkedHashMap<>(), maxEntries, EvictionPolicy.FIFO_OLDEST);
    }

    public static <K> MaidTargetCooldowns<K> forIdentityClearOnFull(int maxEntries) {
        return new MaidTargetCooldowns<>(new IdentityHashMap<>(), maxEntries, EvictionPolicy.CLEAR_ON_FULL);
    }

    /** 推进时钟并在世界时间回拨时清空旧冷却记录，发生回拨时返回 true */
    public boolean advanceTick(long now) {
        boolean rolledBack = now < lastTick;
        if (rolledBack) entries.clear();
        lastTick = now;
        return rolledBack;
    }

    public void purgeExpired(long now, BiPredicate<? super K, ? super Entry> invalidOrDisplaced) {
        entries.entrySet()
            .removeIf(
                item -> now >= item.getValue().retryAt
                    || invalidOrDisplaced != null && invalidOrDisplaced.test(item.getKey(), item.getValue()));
    }

    public void record(K key, long retryAt) {
        record(
            key,
            retryAt,
            EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL,
            EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL,
            EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL,
            null);
    }

    public void record(K key, long retryAt, double x, double y, double z, Object contextRef) {
        Objects.requireNonNull(key, EnhancedSupportIds.ID_KEY);
        if (!entries.containsKey(key) && entries.size() >= maxEntries) {
            if (evictionPolicy == EvictionPolicy.FIFO_OLDEST) {
                var iterator = entries.keySet()
                    .iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            } else {
                entries.clear();
            }
        }
        entries.put(key, new Entry(retryAt, x, y, z, contextRef));
    }

    public boolean contains(K key) {
        return key != null && entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    public void resetClock() {
        entries.clear();
        lastTick = Long.MIN_VALUE;
    }
}
