package com.tianluoqaq.tlmnh.common.ai.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.EvidenceGrade;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.Kind;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;

/** 单只女仆、单个记录会话的有界账本；仅在所属服务端线程使用 */
public final class MaidTelemetryLedger implements MaidTelemetrySink {

    public enum Counter {
        NAV_SUBMITTED,
        NAV_RETURNED,
        NAV_PROCESSED,
        NAV_DURATION_NANOS,
        CANDIDATE_SCANS,
        CANDIDATES_RETURNED,
        CANDIDATES_PROCESSED,
        CANDIDATES_OBSERVED,
        CANDIDATES_ELIGIBLE,
        RELEASE_ATTEMPTS,
        RELEASE_CONFIRMED,
        DAMAGE_PROVISIONAL,
        NATIVE_DAMAGE_RECORDS,
        DAMAGE_EVIDENCE,
        EVIDENCE_NATIVE_COMMIT,
        EVIDENCE_CONTROLLED_CALL,
        EVIDENCE_UNATTRIBUTED_INTERVAL,
        EVIDENCE_AMBIGUOUS,
        ACTION_COMPLETED,
        ACTION_CANCELLED,
        ACTION_FAILED,
        ACTION_TIMED_OUT,
        WORK_SAMPLES
    }

    private final int eventCapacity, candidateCapacity;
    private final List<MaidTelemetryEvent> events = new ArrayList<>();
    private String sessionId, maidId;
    private long epoch, startWorldTick, lastWorldTick, lastSequence = -1;
    private long acceptedEvents, droppedEvents, droppedCandidates, rejectedEvents;
    private int retainedCandidates;
    private boolean enabled = true;
    private Stats stats = new Stats();

    public MaidTelemetryLedger(String sessionId, String maidId, long epoch, long startWorldTick, int eventCapacity,
        int candidateCapacity) {
        if (eventCapacity < 0 || candidateCapacity < 0 || candidateCapacity > eventCapacity)
            throw new IllegalArgumentException("Invalid telemetry capacities");
        this.eventCapacity = eventCapacity;
        this.candidateCapacity = candidateCapacity;
        metadata(sessionId, maidId, epoch, startWorldTick);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Acceptance record(MaidTelemetryEvent event) {
        if (!enabled) return Acceptance.DISABLED;
        Objects.requireNonNull(event, "event");
        if (event.epoch != epoch) return reject(Acceptance.STALE_EPOCH);
        if (event.sequence <= lastSequence) return reject(Acceptance.REPLAYED_SEQUENCE);
        if (event.worldTick < lastWorldTick) return reject(Acceptance.STALE_TICK);
        Stats next;
        long nextAccepted;
        try {
            next = new Stats(stats);
            next.apply(event);
            nextAccepted = Math.addExact(acceptedEvents, 1);
        } catch (ArithmeticException overflow) {
            return reject(Acceptance.COUNTER_OVERFLOW);
        }
        stats = next;
        acceptedEvents = nextAccepted;
        lastSequence = event.sequence;
        lastWorldTick = event.worldTick;
        boolean candidate = event.kind == Kind.CANDIDATE;
        if (events.size() >= eventCapacity || candidate && retainedCandidates >= candidateCapacity) {
            droppedEvents++;
            if (candidate) droppedCandidates++;
            return Acceptance.ACCEPTED_DETAIL_DROPPED;
        }
        events.add(event);
        if (candidate) retainedCandidates++;
        return Acceptance.ACCEPTED;
    }

    private Acceptance reject(Acceptance reason) {
        rejectedEvents++;
        return reason;
    }

    /** epoch是记录会话版本；策略runtime token失效不应调用reset抹去指标 */
    public void reset(String sessionId, String maidId, long newEpoch, long startWorldTick) {
        if (newEpoch <= epoch) throw new IllegalArgumentException("Recording epoch must increase");
        metadata(sessionId, maidId, newEpoch, startWorldTick);
        events.clear();
        stats = new Stats();
        acceptedEvents = droppedEvents = droppedCandidates = rejectedEvents = 0;
        retainedCandidates = 0;
        lastSequence = -1;
        enabled = true;
    }

    public void close() {
        enabled = false;
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    private void metadata(String sessionId, String maidId, long epoch, long tick) {
        String session = MaidTelemetryEvent.identifier(sessionId), maid = MaidTelemetryEvent.identifier(maidId);
        if (session.trim()
            .isEmpty()
            || maid.trim()
                .isEmpty()
            || epoch < 0
            || tick < 0) throw new IllegalArgumentException("Invalid telemetry session metadata");
        this.sessionId = session;
        this.maidId = maid;
        this.epoch = epoch;
        startWorldTick = lastWorldTick = tick;
    }

    public static final class Snapshot {

        public final String schema = "maid-telemetry-v1";
        public final String sessionId, maidId;
        public final long epoch, startWorldTick, lastWorldTick, lastSequence;
        public final long acceptedEvents, droppedEvents, droppedCandidates, rejectedEvents;
        public final int eventCapacity, candidateCapacity, retainedCandidates;
        public final boolean enabled;
        public final List<MaidTelemetryEvent> events;
        public final Map<Counter, Long> counters;
        public final Map<WorkComponent, Long> workCounts, workNanos;
        public final double nativeRecordedDamageAmount, provisionalRequestedDamageAmount;
        public final double observedHealthDelta, observedAbsorptionDelta;
        public final double nativeCommittedHealthLoss, controlledCallHealthLoss;
        public final double nativeCommittedAbsorptionLoss, controlledCallAbsorptionLoss;

        private Snapshot(MaidTelemetryLedger ledger) {
            sessionId = ledger.sessionId;
            maidId = ledger.maidId;
            epoch = ledger.epoch;
            startWorldTick = ledger.startWorldTick;
            lastWorldTick = ledger.lastWorldTick;
            lastSequence = ledger.lastSequence;
            acceptedEvents = ledger.acceptedEvents;
            droppedEvents = ledger.droppedEvents;
            droppedCandidates = ledger.droppedCandidates;
            rejectedEvents = ledger.rejectedEvents;
            eventCapacity = ledger.eventCapacity;
            candidateCapacity = ledger.candidateCapacity;
            retainedCandidates = ledger.retainedCandidates;
            enabled = ledger.enabled;
            events = Collections.unmodifiableList(new ArrayList<>(ledger.events));
            var counts = new EnumMap<Counter, Long>(Counter.class);
            for (var counter : Counter.values()) counts.put(counter, ledger.stats.counters[counter.ordinal()]);
            counters = Collections.unmodifiableMap(counts);
            var workCountMap = new EnumMap<WorkComponent, Long>(WorkComponent.class);
            var workNanosMap = new EnumMap<WorkComponent, Long>(WorkComponent.class);
            for (var component : WorkComponent.values()) {
                workCountMap.put(component, ledger.stats.workCounts[component.ordinal()]);
                workNanosMap.put(component, ledger.stats.workNanos[component.ordinal()]);
            }
            workCounts = Collections.unmodifiableMap(workCountMap);
            workNanos = Collections.unmodifiableMap(workNanosMap);
            nativeRecordedDamageAmount = ledger.stats.nativeRecordedDamageAmount;
            provisionalRequestedDamageAmount = ledger.stats.provisionalRequestedDamageAmount;
            observedHealthDelta = ledger.stats.observedHealthDelta;
            observedAbsorptionDelta = ledger.stats.observedAbsorptionDelta;
            nativeCommittedHealthLoss = ledger.stats.nativeCommittedHealthLoss;
            controlledCallHealthLoss = ledger.stats.controlledCallHealthLoss;
            nativeCommittedAbsorptionLoss = ledger.stats.nativeCommittedAbsorptionLoss;
            controlledCallAbsorptionLoss = ledger.stats.controlledCallAbsorptionLoss;
        }
    }

    private static final class Stats {

        private final long[] counters, workCounts, workNanos;
        private double nativeRecordedDamageAmount, provisionalRequestedDamageAmount;
        private double observedHealthDelta, observedAbsorptionDelta;
        private double nativeCommittedHealthLoss, controlledCallHealthLoss;
        private double nativeCommittedAbsorptionLoss, controlledCallAbsorptionLoss;

        private Stats() {
            counters = new long[Counter.values().length];
            workCounts = new long[WorkComponent.values().length];
            workNanos = new long[WorkComponent.values().length];
        }

        private Stats(Stats previous) {
            counters = previous.counters.clone();
            workCounts = previous.workCounts.clone();
            workNanos = previous.workNanos.clone();
            nativeRecordedDamageAmount = previous.nativeRecordedDamageAmount;
            provisionalRequestedDamageAmount = previous.provisionalRequestedDamageAmount;
            observedHealthDelta = previous.observedHealthDelta;
            observedAbsorptionDelta = previous.observedAbsorptionDelta;
            nativeCommittedHealthLoss = previous.nativeCommittedHealthLoss;
            controlledCallHealthLoss = previous.controlledCallHealthLoss;
            nativeCommittedAbsorptionLoss = previous.nativeCommittedAbsorptionLoss;
            controlledCallAbsorptionLoss = previous.controlledCallAbsorptionLoss;
        }

        private void add(Counter counter, long count) {
            counters[counter.ordinal()] = Math.addExact(counters[counter.ordinal()], count);
        }

        private void apply(MaidTelemetryEvent event) {
            switch (event.kind) {
                case STATE:
                    break;
                case NAV_SUBMITTED:
                    add(Counter.NAV_SUBMITTED, 1);
                    break;
                case NAV_RETURNED:
                    add(Counter.NAV_RETURNED, 1);
                    add(Counter.NAV_DURATION_NANOS, event.navigation.durationNanos);
                    break;
                case NAV_PROCESSED:
                    add(Counter.NAV_PROCESSED, 1);
                    break;
                case CANDIDATE_SCAN:
                    add(Counter.CANDIDATE_SCANS, 1);
                    add(Counter.CANDIDATES_RETURNED, event.candidateScan.returned);
                    add(Counter.CANDIDATES_PROCESSED, event.candidateScan.processed);
                    break;
                case CANDIDATE:
                    add(Counter.CANDIDATES_OBSERVED, 1);
                    if (event.candidate.eligible) add(Counter.CANDIDATES_ELIGIBLE, 1);
                    break;
                case RELEASE_ATTEMPT:
                    add(Counter.RELEASE_ATTEMPTS, 1);
                    break;
                case RELEASE_CONFIRMED:
                    add(Counter.RELEASE_CONFIRMED, 1);
                    break;
                case DAMAGE_PROVISIONAL:
                    add(Counter.DAMAGE_PROVISIONAL, 1);
                    provisionalRequestedDamageAmount = sum(
                        provisionalRequestedDamageAmount,
                        event.provisionalDamage.requestedAmount);
                    break;
                case NATIVE_DAMAGE_RECORD:
                    add(Counter.NATIVE_DAMAGE_RECORDS, 1);
                    nativeRecordedDamageAmount = sum(
                        nativeRecordedDamageAmount,
                        event.nativeDamage.nativeRecordedDamage);
                    break;
                case DAMAGE_EVIDENCE:
                    damage(event.damage);
                    break;
                case ACTION_TERMINAL:
                    switch (event.terminal.outcome) {
                        case COMPLETED:
                            add(Counter.ACTION_COMPLETED, 1);
                            break;
                        case CANCELLED:
                            add(Counter.ACTION_CANCELLED, 1);
                            break;
                        case FAILED:
                            add(Counter.ACTION_FAILED, 1);
                            break;
                        case TIMED_OUT:
                            add(Counter.ACTION_TIMED_OUT, 1);
                            break;
                    }
                    break;
                case WORK_SAMPLE:
                    add(Counter.WORK_SAMPLES, 1);
                    int component = event.work.component.ordinal();
                    workCounts[component] = Math.addExact(workCounts[component], 1);
                    workNanos[component] = Math.addExact(workNanos[component], event.work.durationNanos);
                    break;
            }
        }

        private void damage(MaidTelemetryEvent.DamageEvidence event) {
            add(Counter.DAMAGE_EVIDENCE, 1);
            observedHealthDelta = sum(observedHealthDelta, event.healthDelta);
            if (event.absorptionKnown) observedAbsorptionDelta = sum(observedAbsorptionDelta, event.absorptionDelta);
            double healthLoss = Math.max(0, -event.healthDelta);
            double absorptionLoss = event.absorptionKnown ? Math.max(0, -event.absorptionDelta) : 0;
            if (event.grade == EvidenceGrade.CONFIRMED_NATIVE_COMMIT) {
                add(Counter.EVIDENCE_NATIVE_COMMIT, 1);
                nativeCommittedHealthLoss = sum(nativeCommittedHealthLoss, healthLoss);
                nativeCommittedAbsorptionLoss = sum(nativeCommittedAbsorptionLoss, absorptionLoss);
            } else if (event.grade == EvidenceGrade.CONTROLLED_CALL_DELTA) {
                add(Counter.EVIDENCE_CONTROLLED_CALL, 1);
                controlledCallHealthLoss = sum(controlledCallHealthLoss, healthLoss);
                controlledCallAbsorptionLoss = sum(controlledCallAbsorptionLoss, absorptionLoss);
            } else
                if (event.grade == EvidenceGrade.UNATTRIBUTED_INTERVAL) add(Counter.EVIDENCE_UNATTRIBUTED_INTERVAL, 1);
                else add(Counter.EVIDENCE_AMBIGUOUS, 1);
        }

        private static double sum(double previous, double value) {
            double result = previous + value;
            if (!Double.isFinite(result)) throw new ArithmeticException("Telemetry scalar overflow");
            return result;
        }
    }
}
