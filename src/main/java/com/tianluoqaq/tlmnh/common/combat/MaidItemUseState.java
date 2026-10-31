package com.tianluoqaq.tlmnh.common.combat;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 持续使用只允许一个持有者，普通近战挥手不占用此资源 */
public final class MaidItemUseState {

    public enum Hand {
        MAIN,
        OFF
    }

    public enum Kind {
        BOW,
        SHIELD
    }

    public static final class Lease {

        private final Object owner, item;
        private final Hand hand;
        private final Kind kind;
        private final BooleanSupplier valid;
        private final long generation;

        private Lease(Object owner, Object item, Hand hand, Kind kind, BooleanSupplier valid, long generation) {
            this.owner = owner;
            this.item = item;
            this.hand = hand;
            this.kind = kind;
            this.valid = valid;
            this.generation = generation;
        }

        public Hand getHand() {
            return hand;
        }

        public Kind getKind() {
            return kind;
        }

        public long getGeneration() {
            return generation;
        }
    }

    /** 不包含持有者或物品对象，允许开发报告安全序列化 */
    public static final class Snapshot {

        public final boolean using;
        public final Hand hand;
        public final Kind kind;
        public final long generation;

        private Snapshot(Lease lease, boolean valid, long generation) {
            using = lease != null && valid;
            hand = using ? lease.hand : null;
            kind = using ? lease.kind : null;
            this.generation = generation;
        }
    }

    private final Runnable changed;
    private Lease current;
    private long generation;
    private boolean acquiring;

    public MaidItemUseState(Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    public Lease tryAcquire(Object owner, Object item, Hand hand, Kind kind, BooleanSupplier valid) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(valid, "valid");
        if (acquiring) return null;
        acquiring = true;
        try {
            reconcile();
            var existing = current;
            if (existing != null) return existing.owner == owner && existing.item == item
                && existing.hand == hand
                && existing.kind == kind ? existing : null;
            if (!valid.getAsBoolean() || current != null) return null;
            if (generation < Long.MAX_VALUE) generation++;
            var lease = new Lease(owner, item, hand, kind, valid, generation);
            current = lease;
            try {
                changed.run();
                if (isCurrent(lease)) return lease;
                release(lease);
                return null;
            } catch (RuntimeException | Error failure) {
                try {
                    release(lease);
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } finally {
            acquiring = false;
        }
    }

    public boolean isCurrent(Lease lease) {
        if (lease == null || current != lease) return false;
        boolean valid = lease.valid.getAsBoolean();
        return valid && current == lease;
    }

    /** 取消可以发生在旧授权失效后，但不能擦掉新持有者 */
    public boolean release(Lease lease) {
        if (lease == null || current != lease) return false;
        current = null;
        changed.run();
        return true;
    }

    public void reconcile() {
        var lease = current;
        if (lease == null) return;
        try {
            if (!isCurrent(lease)) release(lease);
        } catch (RuntimeException | Error failure) {
            release(lease);
            throw failure;
        }
    }

    public void invalidate() {
        release(current);
    }

    /** pose 投影只读取当前身份，不触发验证回调或隐式抢占 */
    public Lease peek() {
        return current;
    }

    public Snapshot getSnapshot() {
        var lease = current;
        return new Snapshot(lease, lease != null && isCurrent(lease), generation);
    }
}
