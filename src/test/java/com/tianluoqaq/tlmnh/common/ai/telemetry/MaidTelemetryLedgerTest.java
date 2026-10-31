package com.tianluoqaq.tlmnh.common.ai.telemetry;

import static org.junit.Assert.*;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.EvidenceGrade;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.Kind;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.TerminalOutcome;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryLedger.Counter;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetrySink.Acceptance;

public class MaidTelemetryLedgerTest {

    @Test
    public void defaultSinkIsDisabledAndClosedSessionsIgnoreLateCallbacks() {
        assertFalse(MaidTelemetrySink.NONE.isEnabled());
        assertEquals(Acceptance.DISABLED, MaidTelemetrySink.NONE.record(null));
        var ledger = ledger(4, 0);
        ledger.record(MaidTelemetryEvent.state(2, 0, 100, "follow", null, "started"));
        ledger.close();
        assertFalse(ledger.isEnabled());
        assertEquals(Acceptance.DISABLED, ledger.record(null));
        assertEquals(1, ledger.snapshot().acceptedEvents);
        assertEquals(0, ledger.snapshot().rejectedEvents);
    }

    @Test
    public void detailOverflowNeverStopsActualCountersOrWorkSamples() {
        var ledger = ledger(1, 0);
        assertEquals(
            Acceptance.ACCEPTED,
            ledger.record(
                MaidTelemetryEvent.navigation(2, 0, 100, Kind.NAV_SUBMITTED, "follow", "owner", "native-call", 0)));
        assertEquals(
            Acceptance.ACCEPTED_DETAIL_DROPPED,
            ledger.record(
                MaidTelemetryEvent.navigation(2, 1, 100, Kind.NAV_RETURNED, "follow", "owner", "null-path", 37)));
        ledger
            .record(MaidTelemetryEvent.navigation(2, 2, 100, Kind.NAV_PROCESSED, "follow", "owner", "unreachable", 0));
        ledger.record(MaidTelemetryEvent.work(2, 3, 100, "brain", WorkComponent.BRAIN, 90));
        ledger.record(MaidTelemetryEvent.nativeCommit(2, 4, 100, "bow", "arrow", "target", "raw-only", 1000, 20));
        var actual = ledger.snapshot();
        assertEquals(1, actual.events.size());
        assertEquals(5, actual.acceptedEvents);
        assertEquals(4, actual.droppedEvents);
        count(actual, Counter.NAV_SUBMITTED, 1);
        count(actual, Counter.NAV_RETURNED, 1);
        count(actual, Counter.NAV_PROCESSED, 1);
        count(actual, Counter.NAV_DURATION_NANOS, 37);
        assertEquals(Long.valueOf(1), actual.workCounts.get(WorkComponent.BRAIN));
        assertEquals(Long.valueOf(90), actual.workNanos.get(WorkComponent.BRAIN));
        assertEquals(1000, actual.nativeRecordedDamageAmount, 0);
        assertEquals(0, actual.nativeCommittedHealthLoss, 0);
        assertEquals(0, actual.controlledCallHealthLoss, 0);
        assertEquals(
            Acceptance.REPLAYED_SEQUENCE,
            ledger.record(MaidTelemetryEvent.nativeCommit(2, 4, 101, "bow", "arrow", "target", "replayed", 1000, 20)));
        assertEquals(1000, ledger.snapshot().nativeRecordedDamageAmount, 0);
    }

    @Test
    public void candidateQuotaDoesNotConsumeRemainingGeneralEventCapacityOrTruncateScanCounts() {
        var ledger = ledger(3, 1);
        ledger.record(MaidTelemetryEvent.candidate(2, 0, 100, "target-scan", "first", "visible", null, true));
        assertEquals(
            Acceptance.ACCEPTED_DETAIL_DROPPED,
            ledger.record(MaidTelemetryEvent.candidate(2, 1, 100, "target-scan", "second", "excluded", .25, false)));
        ledger.record(MaidTelemetryEvent.candidateScan(2, 2, 100, "target-scan", "bounded processing", 500, 32));
        ledger.record(MaidTelemetryEvent.state(2, 3, 100, "idle", "", "ordinary event still retained"));
        ledger.record(MaidTelemetryEvent.release(2, 4, 101, true, "bow", "arrow", "first", "actual release"));
        var actual = ledger.snapshot();
        assertEquals(3, actual.events.size());
        assertEquals(0, actual.events.get(0).sequence);
        assertEquals(2, actual.events.get(1).sequence);
        assertEquals(3, actual.events.get(2).sequence);
        assertNull(actual.events.get(0).candidate.score);
        assertEquals(1, actual.retainedCandidates);
        assertEquals(2, actual.droppedEvents);
        assertEquals(1, actual.droppedCandidates);
        count(actual, Counter.CANDIDATES_OBSERVED, 2);
        count(actual, Counter.CANDIDATES_RETURNED, 500);
        count(actual, Counter.CANDIDATES_PROCESSED, 32);
        count(actual, Counter.RELEASE_CONFIRMED, 1);
    }

    @Test
    public void sameTickOrderingIsStableAndReplayOrTimeRegressionNeverConsumesAStamp() {
        var ledger = ledger(8, 0);
        ledger.record(MaidTelemetryEvent.state(2, 0, 100, "life", "cabinet", "selected"));
        ledger.record(MaidTelemetryEvent.state(2, 1, 100, "life", "cabinet", "approach"));
        assertEquals(Acceptance.STALE_TICK, ledger.record(MaidTelemetryEvent.state(2, 2, 99, "life", "", "old")));
        assertEquals(
            Acceptance.REPLAYED_SEQUENCE,
            ledger.record(MaidTelemetryEvent.state(2, 1, 999, "life", "", "replay")));
        assertEquals(
            Acceptance.STALE_EPOCH,
            ledger.record(MaidTelemetryEvent.state(1, 20, 999, "life", "", "old session")));
        assertEquals(100, ledger.snapshot().lastWorldTick);
        assertEquals(1, ledger.snapshot().lastSequence);
        assertEquals(
            Acceptance.ACCEPTED,
            ledger.record(MaidTelemetryEvent.state(2, 2, 100, "life", "", "valid retry")));
        assertEquals(3, ledger.snapshot().rejectedEvents);
        assertEquals(3, ledger.snapshot().acceptedEvents);
    }

    @Test
    public void newRecordingEpochAllowsRestartedWorldClockWithoutMutatingPriorSnapshot() {
        var ledger = ledger(8, 0);
        ledger.record(MaidTelemetryEvent.release(2, 0, 110, true, "bow", "arrow", "target", "release"));
        var before = ledger.snapshot();
        ledger.close();
        ledger.reset("new-session", "same-maid", 3, 0);
        assertTrue(ledger.isEnabled());
        assertEquals(
            Acceptance.STALE_EPOCH,
            ledger.record(MaidTelemetryEvent.state(2, 100, 1000, "bow", "", "late callback")));
        assertEquals(
            Acceptance.ACCEPTED,
            ledger.record(MaidTelemetryEvent.state(3, 0, 0, "idle", "", "clock restarted")));
        count(ledger.snapshot(), Counter.RELEASE_CONFIRMED, 0);
        count(before, Counter.RELEASE_CONFIRMED, 1);
        assertEquals(110, before.lastWorldTick);
        assertEquals(2, before.epoch);
        assertThrows(IllegalArgumentException.class, () -> ledger.reset("duplicate", "maid", 3, 0));
        assertEquals("new-session", ledger.snapshot().sessionId);
        assertThrows(UnsupportedOperationException.class, () -> before.events.clear());
        assertThrows(UnsupportedOperationException.class, () -> before.counters.put(Counter.RELEASE_CONFIRMED, 9L));
        assertThrows(UnsupportedOperationException.class, () -> before.workNanos.put(WorkComponent.SOLVER, 9L));
    }

    @Test
    public void nativeAmountsReleaseDamageEvidenceAndTerminalResultsRemainSeparate() throws Exception {
        var ledger = ledger(20, 0);
        ledger.record(MaidTelemetryEvent.release(2, 0, 100, false, "bow", "arrow", "target", "spawn attempt"));
        ledger.record(MaidTelemetryEvent.release(2, 1, 100, true, "bow", "arrow", "target", "resource commit"));
        ledger.record(
            MaidTelemetryEvent.provisionalDamage(2, 2, 100, "bow", "arrow", "target", "cancellable hurt", 1000));
        var nativeEvent = MaidTelemetryEvent
            .nativeCommit(2, 3, 100, "bow", "arrow", "target", "raw overkill", 1000, 20);
        ledger.record(nativeEvent);
        assertThrows(
            NoSuchFieldException.class,
            () -> nativeEvent.nativeDamage.getClass()
                .getField("healthAfter"));
        assertEquals(0, ledger.snapshot().controlledCallHealthLoss, 0);
        count(ledger.snapshot(), Counter.ACTION_COMPLETED, 0);
        ledger.record(
            MaidTelemetryEvent.damage(
                2,
                4,
                101,
                "bow",
                "arrow",
                "target",
                "controlled overkill",
                EvidenceGrade.CONTROLLED_CALL_DELTA,
                20,
                0,
                true,
                4,
                1));
        var ambiguous = MaidTelemetryEvent.damage(
            2,
            5,
            101,
            "bow",
            "mixed",
            "target",
            "nested other source",
            EvidenceGrade.AMBIGUOUS,
            20,
            0,
            false,
            Double.NaN,
            Double.NaN);
        ledger.record(ambiguous);
        ledger.record(
            MaidTelemetryEvent.damage(
                2,
                6,
                101,
                "interval",
                "",
                "target",
                "cannot attribute",
                EvidenceGrade.UNATTRIBUTED_INTERVAL,
                20,
                5,
                false,
                0,
                0));
        ledger.record(
            MaidTelemetryEvent.damage(
                2,
                7,
                102,
                "controlled",
                "source",
                "target",
                "healed during call",
                EvidenceGrade.CONTROLLED_CALL_DELTA,
                0,
                5,
                false,
                0,
                0));
        var actual = ledger.snapshot();
        assertEquals(1000, actual.nativeRecordedDamageAmount, 0);
        assertEquals(20, actual.controlledCallHealthLoss, 0);
        assertEquals(3, actual.controlledCallAbsorptionLoss, 0);
        assertEquals(0, actual.nativeCommittedHealthLoss, 0);
        assertTrue(ambiguous.damage.ambiguous);
        assertNull(ambiguous.damage.absorptionAfter);
        assertEquals(5, actual.events.get(7).damage.healthDelta, 0);
        count(actual, Counter.EVIDENCE_AMBIGUOUS, 1);
        count(actual, Counter.EVIDENCE_UNATTRIBUTED_INTERVAL, 1);
        count(actual, Counter.ACTION_COMPLETED, 0);
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent
                .terminal(2, 8, 102, "action", "target", "unconfirmed", TerminalOutcome.COMPLETED, false));
        ledger.record(
            MaidTelemetryEvent.damage(
                2,
                8,
                102,
                "native-pair",
                "source",
                "target",
                "independently confirmed pair",
                EvidenceGrade.CONFIRMED_NATIVE_COMMIT,
                8,
                7,
                true,
                3,
                1));
        assertEquals(1, ledger.snapshot().nativeCommittedHealthLoss, 0);
        assertEquals(2, ledger.snapshot().nativeCommittedAbsorptionLoss, 0);
        assertEquals(-51, ledger.snapshot().observedHealthDelta, 0);
        assertEquals(-5, ledger.snapshot().observedAbsorptionDelta, 0);
        count(ledger.snapshot(), Counter.EVIDENCE_NATIVE_COMMIT, 1);
        ledger.record(
            MaidTelemetryEvent
                .terminal(2, 9, 102, "cake-action", "cabinet", "effect checked", TerminalOutcome.COMPLETED, true));
        ledger.record(
            MaidTelemetryEvent
                .terminal(2, 10, 102, "bed-action", "bed", "command cancelled", TerminalOutcome.CANCELLED, false));
        ledger.record(
            MaidTelemetryEvent
                .terminal(2, 11, 102, "path-action", "target", "deadline", TerminalOutcome.TIMED_OUT, false));
        count(ledger.snapshot(), Counter.ACTION_COMPLETED, 1);
        count(ledger.snapshot(), Counter.ACTION_CANCELLED, 1);
        count(ledger.snapshot(), Counter.ACTION_TIMED_OUT, 1);
        count(ledger.snapshot(), Counter.RELEASE_CONFIRMED, 1);
    }

    @Test
    public void invalidNumbersOrCounterOverflowCannotPoisonLaterObservations() {
        var ledger = ledger(0, 0);
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent.candidate(2, 0, 100, "scan", "target", "", Double.NaN, true));
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent
                .provisionalDamage(2, 0, 100, "bow", "arrow", "target", "", Double.POSITIVE_INFINITY));
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent.damage(
                2,
                0,
                100,
                "bow",
                "arrow",
                "target",
                "",
                EvidenceGrade.CONTROLLED_CALL_DELTA,
                Double.MAX_VALUE,
                -Double.MAX_VALUE,
                false,
                0,
                0));
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent.navigation(2, 0, 100, Kind.NAV_PROCESSED, "follow", "owner", "", 1));
        assertEquals(
            Acceptance.ACCEPTED_DETAIL_DROPPED,
            ledger.record(MaidTelemetryEvent.work(2, 0, 100, "brain", WorkComponent.BRAIN, Long.MAX_VALUE)));
        assertEquals(
            Acceptance.COUNTER_OVERFLOW,
            ledger.record(MaidTelemetryEvent.work(2, 1, 100, "brain", WorkComponent.BRAIN, 1)));
        assertEquals(Long.valueOf(1), ledger.snapshot().workCounts.get(WorkComponent.BRAIN));
        assertEquals(Long.valueOf(Long.MAX_VALUE), ledger.snapshot().workNanos.get(WorkComponent.BRAIN));
        assertEquals(1, ledger.snapshot().acceptedEvents);
        assertEquals(0, ledger.snapshot().lastSequence);
        ledger.record(MaidTelemetryEvent.work(2, 2, 101, "solver", WorkComponent.SOLVER, Long.MAX_VALUE));
        count(ledger.snapshot(), Counter.WORK_SAMPLES, 2);
        assertEquals(Long.valueOf(Long.MAX_VALUE), ledger.snapshot().workNanos.get(WorkComponent.SOLVER));
    }

    @Test
    public void boundedTextPreservesUnknownsAndDoesNotSilentlyTruncateIdentifiers() {
        var event = MaidTelemetryEvent.state(2, 0, 100, null, null, new String(new char[600]).replace('\0', 'x'));
        assertEquals("", event.actionId);
        assertEquals("", event.targetId);
        assertTrue(event.detailTruncated);
        assertEquals(512, event.detail.length());
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent.state(2, 0, 100, "action", new String(new char[161]).replace('\0', 'x'), ""));
        assertThrows(
            IllegalArgumentException.class,
            () -> MaidTelemetryEvent.candidateScan(2, 0, 100, "scan", "", 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new MaidTelemetryLedger("session", "maid", 2, 100, 1, 2));
    }

    private static MaidTelemetryLedger ledger(int capacity, int candidateCapacity) {
        return new MaidTelemetryLedger("session", "maid", 2, 100, capacity, candidateCapacity);
    }

    private static void count(MaidTelemetryLedger.Snapshot snapshot, Counter counter, long expected) {
        assertEquals(counter.name(), Long.valueOf(expected), snapshot.counters.get(counter));
    }
}
