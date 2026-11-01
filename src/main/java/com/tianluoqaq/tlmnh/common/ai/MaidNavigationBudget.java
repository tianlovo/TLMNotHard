package com.tianluoqaq.tlmnh.common.ai;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 世界线程上的 FIFO 准入；只保存身份，不排队执行世界回调或保存旧目标 */
public final class MaidNavigationBudget {

    private static final int ABANDONED_TICKS = 2;
    private final int limit;
    private final Map<UUID, Long> waiting = new LinkedHashMap<>();
    private long tick = Long.MIN_VALUE, admitted, deferred;
    private int used;

    public MaidNavigationBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive navigation budget required");
        this.limit = limit;
    }

    public boolean acquire(UUID actor, long now) {
        Objects.requireNonNull(actor, "actor");
        advance(now);
        waiting.put(actor, now);
        if (used < limit && waiting.keySet()
            .iterator()
            .next()
            .equals(actor)) {
            waiting.remove(actor);
            used++;
            admitted++;
            return true;
        }
        deferred++;
        return false;
    }

    public void cancel(UUID actor) {
        waiting.remove(actor);
        // 已消耗额度不能因取消退回，否则同 tick 反复换动作可以突破上限
    }

    private void advance(long now) {
        if (now != tick) {
            if (now < tick) waiting.clear();
            used = 0;
            tick = now;
        }
        for (Iterator<Long> values = waiting.values()
            .iterator(); values.hasNext();) if (now - values.next() > ABANDONED_TICKS) values.remove();
    }

    public int used(long now) {
        advance(now);
        return used;
    }

    public int queued() {
        return waiting.size();
    }

    public long admitted() {
        return admitted;
    }

    public long deferred() {
        return deferred;
    }
}
