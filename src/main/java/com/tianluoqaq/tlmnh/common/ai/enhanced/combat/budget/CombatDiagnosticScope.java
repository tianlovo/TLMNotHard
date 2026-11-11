package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

/** 显式开发QA作用域的纯缓存；不授予额度、准备或动作权限 */
public final class CombatDiagnosticScope implements AutoCloseable {

    private static final Map<Object, CombatDiagnosticScope> ACTIVE = new WeakHashMap<>();

    private final WeakReference<Object> world;
    private final CombatDiagnosticScope previous;
    private final Map<Object, Object> values = new IdentityHashMap<>();
    private boolean closed;
    public int droppedOwners;

    private CombatDiagnosticScope(Object world, CombatDiagnosticScope previous) {
        this.world = new WeakReference<>(world);
        this.previous = previous;
    }

    public static CombatDiagnosticScope open(Object world) {
        if (!DevelopmentFlags.isDevCommandsEnabled() || world == null)
            throw new IllegalStateException(CombatBudgetDiagnostics.MSG_EXPLICIT_DEVELOPMENT_QA_DIAGNOSTICS_REQUIRED);
        var scope = new CombatDiagnosticScope(world, ACTIVE.get(world));
        ACTIVE.put(world, scope);
        return scope;
    }

    public static CombatDiagnosticScope current(Object world) {
        return ACTIVE.get(world);
    }

    public Object get(Object owner) {
        return closed ? null : values.get(owner);
    }

    public void put(Object owner, Object value) {
        if (closed) throw new IllegalStateException(CombatBudgetDiagnostics.MSG_CLOSED_DIAGNOSTIC_SCOPE);
        if (!values.containsKey(owner) && values.size() >= CombatBudgetTuning.MAX_OWNERS) {
            droppedOwners++;
            return;
        }
        values.put(owner, value);
    }

    public Log log(Object owner, int capacity) {
        Log log = (Log) get(owner);
        if (log == null) {
            log = new Log(capacity);
            put(owner, log);
        }
        return log;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Object key = world.get();
        if (key != null && ACTIVE.get(key) == this) {
            if (previous != null && !previous.closed) ACTIVE.put(key, previous);
            else ACTIVE.remove(key);
        }
        values.clear();
    }

    public static final class Event {

        public final String kind, detail;
        public final long tick, sequence;
        public final long[] values;

        Event(long tick, long sequence, String kind, String detail, long[] values) {
            this.tick = tick;
            this.sequence = sequence;
            this.kind = kind;
            this.detail = detail;
            this.values = values;
        }
    }

    public static final class Frame {

        public final long tick;
        public final int dropped;
        public final Event[] events;
        public final Event lastReject;

        Frame(long tick, int dropped, Event[] events, Event lastReject) {
            this.tick = tick;
            this.dropped = dropped;
            this.events = events;
            this.lastReject = lastReject;
        }

        public Frame withoutLastReject() {
            return new Frame(tick, dropped, events, null);
        }

        public Frame withoutEvents() {
            return new Frame(tick, dropped, new Event[0], lastReject);
        }
    }

    public static final class Log {

        private final Event[] events;
        private long tick = Long.MIN_VALUE, sequence;
        private int size, dropped;
        private Event lastReject;

        public Log(int capacity) {
            events = new Event[capacity];
        }

        private void begin(long now) {
            if (now != tick) {
                Arrays.fill(events, null);
                tick = now;
                size = dropped = CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED;
            }
        }

        public boolean hasCapacity(long now) {
            begin(now);
            return size < events.length;
        }

        public boolean hasRemainingCurrentCapacity() {
            return size < events.length;
        }

        /** 调用者必须在构造payload或读取诊断getter之前申请固定槽；拒绝另保留一份 */
        public boolean shouldCapture(long now, String kind) {
            begin(now);
            if (size < events.length) return true;
            dropped++;
            return kind.equals(CombatBudgetIds.KEY_REJECT) || kind.equals(CombatBudgetIds.KEY_STOP);
        }

        public void addCaptured(long now, String kind, String detail, long... values) {
            begin(now);
            boolean reject = kind.equals(CombatBudgetIds.KEY_REJECT) || kind.equals(CombatBudgetIds.KEY_STOP);
            if (size >= events.length && !reject) return;
            Event event = new Event(now, ++sequence, kind, detail, values);
            if (reject) lastReject = event;
            if (size < events.length) events[size++] = event;
        }

        public void add(long now, String kind, String detail, long... values) {
            if (shouldCapture(now, kind)) addCaptured(now, kind, detail, values);
        }

        public Frame snapshot() {
            return new Frame(tick, dropped, Arrays.copyOf(events, size), lastReject);
        }
    }
}
