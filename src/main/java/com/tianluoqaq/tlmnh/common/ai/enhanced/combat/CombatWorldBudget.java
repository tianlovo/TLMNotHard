package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** 主线程纯调度账本；连续采集准备与预演分开，不保存实体或查询世界 */
public final class CombatWorldBudget {

    public enum Phase {
        WAITING,
        PREPARING,
        READY,
        FULL
    }

    public static final int PREPARATIONS_PER_TICK = 8, PREPARATION_TICKS = 6;
    private static final int REQUEST_TTL = 2, FULL_PER_TICK = 2;
    private static final Map<Object, State> WORLDS = new WeakHashMap<>();

    private CombatWorldBudget() {}

    public static CombatWorkQuota heartbeat(Object world, UUID id, long now, boolean wantsFull) {
        State state = WORLDS.get(world);
        if (state == null) {
            state = new State();
            WORLDS.put(world, state);
        }
        state.begin(now);
        Request r = state.requests.get(id);
        if (r == null) {
            r = new Request(id, now, state.nextOrder++, ++state.nextRequestSerial);
            state.requests.put(id, r);
        }
        r.heartbeat = now;
        if (wantsFull) {
            r.fullRequested = now;
            if (!r.waiting) {
                r.waiting = true;
                r.since = now;
            }
        }
        if (r.quota == null || r.quota.tick != now) r.quota = new CombatWorkQuota(now, false, state.ledger);
        if (r.preparing) r.quota.prepare(r.round);
        int preparationAhead = -1, preparationBefore = -1;
        if (r.waiting && !r.ready
            && !r.preparing
            && !state.preparations.contains(id)
            && (preparationBefore = state.preparations.size()) < PREPARATIONS_PER_TICK) {
            int ahead = 0;
            for (Request other : state.requests.values()) if (other != r && other.waiting
                && !other.ready
                && !other.preparing
                && !state.preparations.contains(other.id)
                && before(other, r)) ahead++;
            preparationAhead = ahead;
            if (ahead < PREPARATIONS_PER_TICK - state.preparations.size()) {
                r.preparing = true;
                r.started = now;
                r.expires = now + PREPARATION_TICKS;
                r.samples = 0;
                r.round = ++state.nextRound;
                state.preparations.add(id);
                r.quota.prepare(r.round);
            }
        }
        int affordability = -1, fullAhead = -1, fullBefore = state.full;
        if (wantsFull && r.ready
            && (affordability = affordable(r, now, state.ledger) ? 1 : 0) == 1
            && !r.granted(now)
            && state.full < FULL_PER_TICK) {
            int ahead = 0;
            for (Request other : state.requests.values()) if (other != r && other.waiting
                && other.ready
                && affordable(other, now, state.ledger)
                && !other.granted(now)
                && beforeFull(other, r)) ahead++;
            fullAhead = ahead;
            if (ahead < FULL_PER_TICK - state.full) {
                state.full++;
                r.quota.upgrade();
                state.previousFull.add(r);
            }
        }
        trace(
            world,
            state,
            r,
            now,
            "HEARTBEAT",
            wantsFull ? 1 : 0,
            -1,
            affordability,
            fullAhead,
            fullBefore,
            preparationAhead,
            preparationBefore,
            -1);
        return r.quota;
    }

    /** 只接收观测方已经计算的覆盖状态，不续期或查询世界 */
    public static void prepared(Object world, UUID id, long now, boolean ready, long oldestCoverage) {
        prepared(world, id, now, ready, oldestCoverage, 0, 0);
    }

    public static void prepared(Object world, UUID id, long now, boolean ready, long oldestCoverage, int friendDebt,
        int knownFriends) {
        State state = WORLDS.get(world);
        Request r = state == null || state.tick != now ? null : state.requests.get(id);
        if (r == null || !r.waiting) return;
        r.oldestCoverage = oldestCoverage;
        r.debtAt = now;
        r.friendDebt = friendDebt;
        r.knownFriends = knownFriends;
        r.ready = ready && fresh(now, oldestCoverage);
        if (r.ready && r.preparing) {
            r.preparing = false;
            state.rotatePreparation(r);
        }
        trace(world, state, r, now, "PREPARED", -1, ready ? 1 : 0, -1, -1, state.full, -1, -1, -1);
    }

    /** 同tick的档位升级只算一个真实采样tick */
    public static void sampled(Object world, UUID id, long now, long round) {
        State state = WORLDS.get(world);
        Request r = state == null || state.tick != now ? null : state.requests.get(id);
        if (r != null && r.preparing && r.round == round && r.sampledAt != now) {
            r.sampledAt = now;
            r.samples++;
        }
        if (r != null) trace(world, state, r, now, "SAMPLED", -1, -1, -1, -1, state.full, -1, -1, round);
    }

    public static void completed(Object world, UUID id, long now) {
        State state = WORLDS.get(world);
        Request r = state == null ? null : state.requests.get(id);
        if (r != null && r.granted(now)) {
            r.completedAt = now;
            r.waiting = r.ready = false;
            if (r.preparing) {
                r.preparing = false;
                state.rotatePreparation(r);
            }
        }
        if (r != null) trace(world, state, r, now, "COMPLETED", -1, -1, -1, -1, state.full, -1, -1, -1);
    }

    private static void trace(Object world, State state, Request r, long now, String method, int wants, int readyInput,
        int affordability, int ahead, int fullBefore, int preparationAhead, int preparationBefore,
        long sampledRoundInput) {
        CombatDiagnosticScope scope = CombatDiagnosticScope.current(world);
        if (scope == null) return;
        var log = scope.log(r, 24);
        if (!log.shouldCapture(now, method)) return;
        int width = CombatWorkQuota.Work.values().length;
        long[] v = new long[31 + width * 3];
        long[] head = { r.serial, wants, readyInput, affordability, ahead, fullBefore, state.full, r.since, r.order,
            r.fullOrder, r.round, r.started, r.expires, r.sampledAt, r.samples, r.oldestCoverage, r.friendDebt,
            r.knownFriends, r.debtAt, r.waiting ? 1 : 0, r.ready ? 1 : 0, r.preparing ? 1 : 0,
            r.quota != null && r.quota.full ? 1 : 0, r.completedAt, r.quota != null && r.quota.preparing ? 1 : 0,
            preparationAhead, preparationBefore, sampledRoundInput, r.quota == null ? Long.MIN_VALUE : r.quota.tick,
            r.fullRequested, r.heartbeat };
        System.arraycopy(head, 0, v, 0, head.length);
        int[] used = r.quota == null ? new int[width] : r.quota.counts();
        int[] denied = r.quota == null ? new int[width] : r.quota.denials();
        for (int i = 0; i < width; i++) {
            v[31 + i] = used[i];
            v[31 + width + i] = state.ledger.used[i];
            v[31 + width * 2 + i] = denied[i];
        }
        log.addCaptured(now, method, "-1=NOT_EVALUATED; head31/localUsed15/worldUsed15/localDenied15", v);
    }

    /** 只读取显式QA作用域的缓存，不启动tick或续租 */
    public static RequestDiagnostic requestDiagnostic(Object world, UUID id) {
        CombatDiagnosticScope scope = CombatDiagnosticScope.current(world);
        if (scope == null) return null;
        State state = WORLDS.get(world);
        Request r = state == null ? null : state.requests.get(id);
        if (r == null) return null;
        Object cached = scope.get(r);
        return new RequestDiagnostic(
            cached instanceof CombatDiagnosticScope.Log log ? log.snapshot() : null,
            r.quota == null ? null : r.quota.counts(),
            r.quota == null ? null : r.quota.denials());
    }

    public static final class RequestDiagnostic {

        public final CombatDiagnosticScope.Frame calls;
        public final int[] endUsed, endDenied;

        private RequestDiagnostic(CombatDiagnosticScope.Frame calls, int[] used, int[] denied) {
            this.calls = calls;
            endUsed = used;
            endDenied = denied;
        }
    }

    public static void unload(Object world) {
        WORLDS.remove(world);
    }

    public static void cancel(Object world, UUID id) {
        State state = WORLDS.get(world);
        if (state != null) state.requests.remove(id);
        // 本tick已发准备档不可回收给第九个身份
    }

    public static Diagnostics diagnostics(Object world, long now) {
        State state = WORLDS.get(world);
        return new Diagnostics(state, now);
    }

    private static boolean fresh(long now, long at) {
        return at != Long.MIN_VALUE && at <= now && now - at >= 0 && now - at <= CombatSnapshot.OBSERVATION_TTL;
    }

    private static boolean affordable(Request r, long now, CombatWorkQuota.Ledger ledger) {
        int debt = r.debtAt == now ? r.friendDebt : r.knownFriends;
        return r.quota == null || r.quota.tick != now ? CombatWorkQuota.canRefreshFriends(ledger, debt)
            : r.quota.canRefreshFriends(debt);
    }

    private static boolean before(Request a, Request b) {
        return a.order < b.order || a.order == b.order && a.id.compareTo(b.id) < 0;
    }

    private static boolean beforeFull(Request a, Request b) {
        return a.fullOrder < b.fullOrder || a.fullOrder == b.fullOrder && a.id.compareTo(b.id) < 0;
    }

    public static final class Entry {

        public final UUID id;
        public final Phase phase;
        public final long since, preparationRound, startedAt, expiresAt, oldestCoverage;
        public final long requestSerial, completedAt;
        public final boolean pending;
        public final int sampledTicks;

        private Entry(Request r, long now) {
            id = r.id;
            requestSerial = r.serial;
            pending = r.waiting;
            completedAt = r.completedAt;
            phase = r.granted(now) ? Phase.FULL : r.ready ? Phase.READY : r.preparing ? Phase.PREPARING : Phase.WAITING;
            since = r.since;
            preparationRound = r.round;
            startedAt = r.started;
            expiresAt = r.expires;
            oldestCoverage = r.oldestCoverage;
            sampledTicks = r.samples;
        }
    }

    /** 只复制已有状态，不启动下一tick、不登记请求或续租 */
    public static final class Diagnostics {

        public final long tick;
        public final int fullGrants, requests;
        public final int[] used;
        public final UUID[] granted, requested, renewed, preparations, ready;
        public final Entry[] entries;

        private Diagnostics(State state, long now) {
            boolean active = state != null && state.tick == now;
            tick = now;
            fullGrants = active ? state.full : 0;
            requests = state == null ? 0 : state.requests.size();
            used = active ? state.ledger.used.clone() : new int[CombatWorkQuota.Work.values().length];
            List<UUID> g = new ArrayList<>(), q = new ArrayList<>(), h = new ArrayList<>(), p = new ArrayList<>(),
                rd = new ArrayList<>();
            List<Entry> rows = new ArrayList<>();
            if (state != null) {
                if (active) {
                    p.addAll(state.preparations);
                    for (Request granted : state.previousFull) g.add(granted.id);
                }
                for (Request r : state.requests.values()) {
                    if (r.fullRequested == now) q.add(r.id);
                    if (r.heartbeat == now) h.add(r.id);
                    if (r.ready) rd.add(r.id);
                    rows.add(new Entry(r, now));
                }
            }
            granted = ids(g);
            requested = ids(q);
            renewed = ids(h);
            preparations = ids(p);
            ready = ids(rd);
            rows.sort((a, b) -> a.id.compareTo(b.id));
            entries = rows.toArray(new Entry[0]);
        }

        private static UUID[] ids(List<UUID> rows) {
            java.util.Collections.sort(rows);
            return rows.toArray(new UUID[0]);
        }
    }

    private static final class State {

        long tick = Long.MIN_VALUE, nextOrder, nextRound, nextRequestSerial;
        int full;
        CombatWorkQuota.Ledger ledger = new CombatWorkQuota.Ledger();
        final Map<UUID, Request> requests = new HashMap<>();
        final Set<UUID> preparations = new HashSet<>();
        final List<Request> previousFull = new ArrayList<>(), previousPreparation = new ArrayList<>();

        void rotatePreparation(Request r) {
            if (!previousPreparation.contains(r)) previousPreparation.add(r);
        }

        void begin(long now) {
            if (tick == now) return;
            if (now < tick) {
                requests.clear();
                previousFull.clear();
                previousPreparation.clear();
            }
            for (Request r : previousFull) if (requests.get(r.id) == r) r.fullOrder = nextOrder++;
            for (Request r : previousPreparation) if (requests.get(r.id) == r) r.order = nextOrder++;
            previousFull.clear();
            previousPreparation.clear();
            requests.values()
                .removeIf(r -> now - r.heartbeat > REQUEST_TTL);
            tick = now;
            full = 0;
            ledger = new CombatWorkQuota.Ledger();
            preparations.clear();
            List<Request> ordered = new ArrayList<>(requests.values());
            ordered.sort((a, b) -> before(a, b) ? -1 : before(b, a) ? 1 : 0);
            for (Request r : ordered) {
                if (r.ready && (!fresh(now, r.oldestCoverage))) {
                    r.ready = false;
                    // READY离开准备档时已轮转；未服务的覆盖过期不能再次把同一请求移到队尾
                }
                if (r.preparing && (now >= r.expires || r.samples >= PREPARATION_TICKS)) {
                    r.preparing = false;
                    r.order = nextOrder++;
                }
                if (r.preparing) preparations.add(r.id);
            }
        }
    }

    private static final class Request {

        final UUID id;
        final long serial;
        long completedAt = Long.MIN_VALUE;
        long heartbeat, since, order, fullOrder, fullRequested = Long.MIN_VALUE;
        long round, started, expires, sampledAt = Long.MIN_VALUE, oldestCoverage = Long.MIN_VALUE;
        long debtAt = Long.MIN_VALUE;
        int samples, friendDebt, knownFriends;
        boolean waiting, preparing, ready;
        CombatWorkQuota quota;

        Request(UUID id, long now, long order, long serial) {
            this.id = id;
            this.serial = serial;
            heartbeat = since = now;
            this.order = fullOrder = order;
        }

        boolean granted(long now) {
            return quota != null && quota.tick == now && quota.full;
        }
    }
}
