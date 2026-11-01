package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 世界线程上的扫描FIFO，只持有纯身份；准入不排队执行世界回调 */
public final class MaidSensingBudget {

    public enum Kind {
        LIVING,
        PICKUP,
        LIFE,
        AIR,
        AMBIENT
    }

    public static final class Request {

        public final UUID actor, scope;
        public final Kind kind;

        public Request(UUID actor, UUID scope, Kind kind) {
            this.actor = Objects.requireNonNull(actor, "actor");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Request r && actor.equals(r.actor) && scope.equals(r.scope) && kind == r.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(actor, scope, kind);
        }
    }

    private static final int ABANDONED_TICKS = 2;
    private final int limit;
    private final Map<Request, Long> waiting = new LinkedHashMap<>();
    private final EnumMap<Kind, Long> admissions = new EnumMap<>(Kind.class);
    private long tick = Long.MIN_VALUE, deferred;
    private int used;

    public MaidSensingBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive sensing budget required");
        this.limit = limit;
    }

    public boolean acquire(Request request, long now) {
        Objects.requireNonNull(request, "request");
        advance(now);
        waiting.put(request, now);
        if (used < limit && waiting.keySet()
            .iterator()
            .next()
            .equals(request)) {
            waiting.remove(request);
            used++;
            admissions.merge(request.kind, 1L, Long::sum);
            return true;
        }
        deferred++;
        return false;
    }

    public void cancel(Request request) {
        waiting.remove(request);
    }

    public void cancelScope(UUID scope) {
        waiting.keySet()
            .removeIf(request -> request.scope.equals(scope));
        // 已消耗额度不返还，反复切换动作不能突破当前tick上限
    }

    public void clear() {
        waiting.clear();
    }

    private void advance(long now) {
        if (now != tick) {
            if (now < tick) waiting.clear();
            tick = now;
            used = 0;
        }
        for (Iterator<Long> i = waiting.values()
            .iterator(); i.hasNext();) if (now - i.next() > ABANDONED_TICKS) i.remove();
    }

    public int used(long now) {
        advance(now);
        return used;
    }

    public int queued() {
        return waiting.size();
    }

    public boolean queued(Request request) {
        return waiting.containsKey(request);
    }

    public long admitted(Kind kind) {
        return admissions.getOrDefault(kind, 0L);
    }

    public long deferred() {
        return deferred;
    }
}
