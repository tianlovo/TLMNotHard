package com.tianluoqaq.tlmnh.common.ai.telemetry;

import java.util.Objects;

/** 不持有游戏对象的外部观察事实；释放、伤害和动作完成是不同证据 */
public final class MaidTelemetryEvent {

    public enum Kind {
        STATE,
        NAV_SUBMITTED,
        NAV_RETURNED,
        NAV_PROCESSED,
        CANDIDATE_SCAN,
        CANDIDATE,
        RELEASE_ATTEMPT,
        RELEASE_CONFIRMED,
        DAMAGE_PROVISIONAL,
        DAMAGE_EVIDENCE,
        NATIVE_DAMAGE_RECORD,
        ACTION_TERMINAL,
        WORK_SAMPLE
    }

    public enum EvidenceGrade {
        CONFIRMED_NATIVE_COMMIT,
        CONTROLLED_CALL_DELTA,
        UNATTRIBUTED_INTERVAL,
        AMBIGUOUS
    }

    public enum TerminalOutcome {
        COMPLETED,
        CANCELLED,
        FAILED,
        TIMED_OUT
    }

    public enum WorkComponent {
        BRAIN,
        SENSOR,
        NATIVE_PATH,
        SOLVER
    }

    public final long epoch, sequence, worldTick;
    public final Kind kind;
    public final String actionId, sourceId, targetId, detail;
    public final boolean detailTruncated;
    public final Navigation navigation;
    public final CandidateScan candidateScan;
    public final Candidate candidate;
    public final ProvisionalDamage provisionalDamage;
    public final DamageEvidence damage;
    public final NativeDamageRecord nativeDamage;
    public final Terminal terminal;
    public final WorkSample work;

    private MaidTelemetryEvent(long epoch, long sequence, long tick, Kind kind, String action, String source,
        String target, String detail, Payload payload) {
        if (epoch < 0 || sequence < 0 || tick < 0) throw new IllegalArgumentException("Negative telemetry stamp");
        this.epoch = epoch;
        this.sequence = sequence;
        worldTick = tick;
        this.kind = Objects.requireNonNull(kind, "kind");
        actionId = identifier(action);
        sourceId = identifier(source);
        targetId = identifier(target);
        var description = detail == null ? "" : detail;
        detailTruncated = description.length() > 512;
        int end = Math.min(description.length(), 512);
        if (end < description.length() && end > 0 && Character.isHighSurrogate(description.charAt(end - 1))) end--;
        this.detail = description.substring(0, end);
        navigation = payload instanceof Navigation value ? value : null;
        candidateScan = payload instanceof CandidateScan value ? value : null;
        candidate = payload instanceof Candidate value ? value : null;
        provisionalDamage = payload instanceof ProvisionalDamage value ? value : null;
        damage = payload instanceof DamageEvidence value ? value : null;
        nativeDamage = payload instanceof NativeDamageRecord value ? value : null;
        terminal = payload instanceof Terminal value ? value : null;
        work = payload instanceof WorkSample value ? value : null;
    }

    public static MaidTelemetryEvent state(long epoch, long sequence, long tick, String action, String target,
        String detail) {
        return new MaidTelemetryEvent(epoch, sequence, tick, Kind.STATE, action, "", target, detail, null);
    }

    /** RETURNED表示native调用已返回，PROCESSED表示调用方已处理结果，不等于路径成功 */
    public static MaidTelemetryEvent navigation(long epoch, long sequence, long tick, Kind phase, String action,
        String target, String outcome, long durationNanos) {
        if (phase != Kind.NAV_SUBMITTED && phase != Kind.NAV_RETURNED && phase != Kind.NAV_PROCESSED)
            throw new IllegalArgumentException("Not a navigation phase");
        if (durationNanos < 0 || phase != Kind.NAV_RETURNED && durationNanos != 0)
            throw new IllegalArgumentException("Navigation duration belongs to RETURNED only");
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            phase,
            action,
            "",
            target,
            outcome,
            new Navigation(durationNanos));
    }

    public static MaidTelemetryEvent candidateScan(long epoch, long sequence, long tick, String action, String detail,
        int returned, int processed) {
        if (returned < 0 || processed < 0 || processed > returned)
            throw new IllegalArgumentException("Invalid scan counts");
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.CANDIDATE_SCAN,
            action,
            "",
            "",
            detail,
            new CandidateScan(returned, processed));
    }

    /** 未计算score时传null，不能以编造的零分代替未观测值 */
    public static MaidTelemetryEvent candidate(long epoch, long sequence, long tick, String action, String target,
        String detail, Double score, boolean eligible) {
        if (score != null) finite(score);
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.CANDIDATE,
            action,
            "",
            target,
            detail,
            new Candidate(score, eligible));
    }

    /** confirmed只由资源结算后的实际释放确认提供，spawn尝试不算成功释放 */
    public static MaidTelemetryEvent release(long epoch, long sequence, long tick, boolean confirmed, String action,
        String projectileId, String target, String detail) {
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            confirmed ? Kind.RELEASE_CONFIRMED : Kind.RELEASE_ATTEMPT,
            action,
            projectileId,
            target,
            detail,
            null);
    }

    /** LivingHurt等可取消阶段只能产生候选，不能由此推算最终healthAfter */
    public static MaidTelemetryEvent provisionalDamage(long epoch, long sequence, long tick, String action,
        String sourceId, String target, String detail, double requestedAmount) {
        finite(requestedAmount);
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.DAMAGE_PROVISIONAL,
            action,
            sourceId,
            target,
            detail,
            new ProvisionalDamage(requestedAmount));
    }

    /** before/after必须来自外部已核实观察；负delta表示损血，正delta表示治疗 */
    public static MaidTelemetryEvent damage(long epoch, long sequence, long tick, String action, String sourceId,
        String target, String detail, EvidenceGrade grade, double healthBefore, double healthAfter,
        boolean absorptionKnown, double absorptionBefore, double absorptionAfter) {
        Objects.requireNonNull(grade, "grade");
        finite(healthBefore);
        finite(healthAfter);
        finite(healthAfter - healthBefore);
        if (absorptionKnown) {
            finite(absorptionBefore);
            finite(absorptionAfter);
            finite(absorptionAfter - absorptionBefore);
        }
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.DAMAGE_EVIDENCE,
            action,
            sourceId,
            target,
            detail,
            new DamageEvidence(grade, healthBefore, healthAfter, absorptionKnown, absorptionBefore, absorptionAfter));
    }

    /** 原生CombatTracker等提交的raw damage可能过量，不伪造缺失的healthAfter */
    public static MaidTelemetryEvent nativeCommit(long epoch, long sequence, long tick, String action, String sourceId,
        String target, String detail, double nativeRecordedDamage, double healthBefore) {
        finite(nativeRecordedDamage);
        finite(healthBefore);
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.NATIVE_DAMAGE_RECORD,
            action,
            sourceId,
            target,
            detail,
            new NativeDamageRecord(nativeRecordedDamage, healthBefore));
    }

    public static MaidTelemetryEvent terminal(long epoch, long sequence, long tick, String actionId, String target,
        String detail, TerminalOutcome outcome, boolean effectConfirmed) {
        Objects.requireNonNull(outcome, "outcome");
        if (identifier(actionId).trim()
            .isEmpty()) throw new IllegalArgumentException("Terminal action ID required");
        if (outcome == TerminalOutcome.COMPLETED && !effectConfirmed)
            throw new IllegalArgumentException("Completion needs explicit effect confirmation");
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.ACTION_TERMINAL,
            actionId,
            "",
            target,
            detail,
            new Terminal(outcome, effectConfirmed));
    }

    /** BRAIN可包含其他component耗时，各项不能直接相加当整服或总AI耗时 */
    public static MaidTelemetryEvent work(long epoch, long sequence, long tick, String action, WorkComponent component,
        long durationNanos) {
        if (durationNanos < 0) throw new IllegalArgumentException("Negative work duration");
        return new MaidTelemetryEvent(
            epoch,
            sequence,
            tick,
            Kind.WORK_SAMPLE,
            action,
            "",
            "",
            "",
            new WorkSample(Objects.requireNonNull(component, "component"), durationNanos));
    }

    static String identifier(String value) {
        var text = value == null ? "" : value;
        if (text.length() > 160) throw new IllegalArgumentException("Telemetry identifier exceeds 160 characters");
        return text;
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite telemetry value");
    }

    private interface Payload {
    }

    public static final class Navigation implements Payload {

        public final long durationNanos;

        private Navigation(long duration) {
            durationNanos = duration;
        }
    }

    public static final class CandidateScan implements Payload {

        public final int returned, processed;

        private CandidateScan(int returned, int processed) {
            this.returned = returned;
            this.processed = processed;
        }
    }

    public static final class Candidate implements Payload {

        public final Double score;
        public final boolean eligible;

        private Candidate(Double score, boolean eligible) {
            this.score = score;
            this.eligible = eligible;
        }
    }

    public static final class ProvisionalDamage implements Payload {

        public final double requestedAmount;

        private ProvisionalDamage(double amount) {
            requestedAmount = amount;
        }
    }

    public static final class DamageEvidence implements Payload {

        public final EvidenceGrade grade;
        public final double healthBefore, healthAfter, healthDelta;
        public final boolean absorptionKnown, ambiguous;
        public final Double absorptionBefore, absorptionAfter, absorptionDelta;

        private DamageEvidence(EvidenceGrade grade, double before, double after, boolean absorptionKnown,
            double absorptionBefore, double absorptionAfter) {
            this.grade = grade;
            healthBefore = before;
            healthAfter = after;
            healthDelta = after - before;
            this.absorptionKnown = absorptionKnown;
            this.absorptionBefore = absorptionKnown ? absorptionBefore : null;
            this.absorptionAfter = absorptionKnown ? absorptionAfter : null;
            absorptionDelta = absorptionKnown ? absorptionAfter - absorptionBefore : null;
            ambiguous = grade == EvidenceGrade.UNATTRIBUTED_INTERVAL || grade == EvidenceGrade.AMBIGUOUS;
        }
    }

    public static final class NativeDamageRecord implements Payload {

        public final double nativeRecordedDamage, healthBefore;

        private NativeDamageRecord(double amount, double before) {
            nativeRecordedDamage = amount;
            healthBefore = before;
        }
    }

    public static final class Terminal implements Payload {

        public final TerminalOutcome outcome;
        public final boolean effectConfirmed;

        private Terminal(TerminalOutcome outcome, boolean confirmed) {
            this.outcome = outcome;
            effectConfirmed = confirmed;
        }
    }

    public static final class WorkSample implements Payload {

        public final WorkComponent component;
        public final long durationNanos;

        private WorkSample(WorkComponent component, long duration) {
            this.component = component;
            durationNanos = duration;
        }
    }
}
