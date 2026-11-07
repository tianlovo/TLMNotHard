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
            r = new Request(id, now, state.nextOrder++);
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
        if (r.waiting && !r.ready
            && !r.preparing
            && !state.preparations.contains(id)
            && state.preparations.size() < PREPARATIONS_PER_TICK) {
            int ahead = 0;
            for (Request other : state.requests.values()) if (other != r && other.waiting
                && !other.ready
                && !other.preparing
                && !state.preparations.contains(other.id)
                && before(other, r)) ahead++;
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
        if (wantsFull && r.ready && affordable(r, now, state.ledger) && !r.granted(now) && state.full < FULL_PER_TICK) {
            int ahead = 0;
            for (Request other : state.requests.values()) if (other != r && other.waiting
                && other.ready
                && affordable(other, now, state.ledger)
                && !other.granted(now)
                && beforeFull(other, r)) ahead++;
            if (ahead < FULL_PER_TICK - state.full) {
                state.full++;
                r.quota.upgrade();
                state.previousFull.add(r);
            }
        }
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
    }

    /** 同tick的档位升级只算一个真实采样tick */
    public static void sampled(Object world, UUID id, long now, long round) {
        State state = WORLDS.get(world);
        Request r = state == null || state.tick != now ? null : state.requests.get(id);
        if (r != null && r.preparing && r.round == round && r.sampledAt != now) {
            r.sampledAt = now;
            r.samples++;
        }
    }

    public static void completed(Object world, UUID id, long now) {
        State state = WORLDS.get(world);
        Request r = state == null ? null : state.requests.get(id);
        if (r != null && r.granted(now)) {
            r.waiting = r.ready = false;
            if (r.preparing) {
                r.preparing = false;
                state.rotatePreparation(r);
            }
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
        public final int sampledTicks;

        private Entry(Request r, long now) {
            id = r.id;
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

        long tick = Long.MIN_VALUE, nextOrder, nextRound;
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
                    r.order = nextOrder++;
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
        long heartbeat, since, order, fullOrder, fullRequested = Long.MIN_VALUE;
        long round, started, expires, sampledAt = Long.MIN_VALUE, oldestCoverage = Long.MIN_VALUE;
        long debtAt = Long.MIN_VALUE;
        int samples, friendDebt, knownFriends;
        boolean waiting, preparing, ready;
        CombatWorkQuota quota;

        Request(UUID id, long now, long order) {
            this.id = id;
            heartbeat = since = now;
            this.order = fullOrder = order;
        }

        boolean granted(long now) {
            return quota != null && quota.tick == now && quota.full;
        }
    }
}
