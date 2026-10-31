package com.tianluoqaq.tlmnh.common.ai;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.EvidenceGrade;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.Kind;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.TerminalOutcome;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent.WorkComponent;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryLedger;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetrySink.Acceptance;

/** 默认关闭的单实体记录入口；采集失败只使记录无效，不能改变游戏动作的返回结果 */
public final class MaidTelemetryChannel {

    private static final int PROJECTILE_LIMIT = 256;
    private final EntityMaid maid;
    private final Set<UUID> projectiles = new HashSet<>();
    private final ArrayDeque<UUID> projectileOrder = new ArrayDeque<>();
    private MaidTelemetryLedger ledger;
    private World recordingWorld;
    private UUID recordingMaid;
    private long epoch, sequence, projectileBindingsDropped;
    private String fault;

    public MaidTelemetryChannel(EntityMaid maid) {
        this.maid = maid;
    }

    public void start(String sessionId, int eventCapacity, int candidateCapacity) {
        if (isEnabled() || maid.worldObj.isRemote)
            throw new IllegalStateException("Server recording is already active or unavailable");
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("Recording epoch exhausted");
        recordingWorld = maid.worldObj;
        recordingMaid = maid.getUniqueID();
        ledger = new MaidTelemetryLedger(
            sessionId,
            recordingMaid.toString(),
            ++epoch,
            recordingWorld.getTotalWorldTime(),
            eventCapacity,
            candidateCapacity);
        sequence = projectileBindingsDropped = 0;
        fault = null;
        projectiles.clear();
        projectileOrder.clear();
    }

    public boolean isEnabled() {
        return ledger != null && ledger.isEnabled() && fault == null;
    }

    public String getFault() {
        return fault;
    }

    public long getProjectileBindingsDropped() {
        return projectileBindingsDropped;
    }

    public boolean isConfirmedProjectile(EntityArrow arrow) {
        return projectiles.contains(arrow.getUniqueID());
    }

    public MaidTelemetryLedger.Snapshot snapshot() {
        if (ledger == null) throw new IllegalStateException("No recording session");
        return ledger.snapshot();
    }

    public void close() {
        if (ledger != null) ledger.close();
        recordingWorld = null;
        projectiles.clear();
        projectileOrder.clear();
    }

    public void invalidate(String reason) {
        if (ledger == null) return;
        fault = reason;
        ledger.close();
    }

    public void state(String action, String target, String detail) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.state(s.epoch, s.sequence, s.tick, action, target, detail));
    }

    public void scan(String action, int returned, int processed, String detail) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.candidateScan(s.epoch, s.sequence, s.tick, action, detail, returned, processed));
    }

    public void candidate(String action, Entity target, boolean eligible, String detail) {
        if (!isEnabled()) return;
        candidate(action, id(target), eligible, detail);
    }

    public void candidate(String action, String target, boolean eligible, String detail) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.candidate(s.epoch, s.sequence, s.tick, action, target, detail, null, eligible));
    }

    public void navigation(Kind phase, String action, String target, String outcome, long nanos) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.navigation(s.epoch, s.sequence, s.tick, phase, action, target, outcome, nanos));
    }

    public void work(String action, WorkComponent component, long nanos) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.work(s.epoch, s.sequence, s.tick, action, component, nanos));
    }

    public void releaseAttempt(Entity target) {
        if (!isEnabled()) return;
        emit(
            s -> MaidTelemetryEvent
                .release(s.epoch, s.sequence, s.tick, false, "bow", "", id(target), "release_requested"));
    }

    public void releaseConfirmed(EntityArrow arrow, Entity target) {
        if (!isEnabled()) return;
        UUID identifier = arrow.getUniqueID();
        if (projectiles.add(identifier)) {
            projectileOrder.addLast(identifier);
            if (projectileOrder.size() > PROJECTILE_LIMIT) {
                projectiles.remove(projectileOrder.removeFirst());
                projectileBindingsDropped++;
            }
        }
        emit(
            s -> MaidTelemetryEvent.release(
                s.epoch,
                s.sequence,
                s.tick,
                true,
                "bow",
                id(arrow),
                id(target),
                "spawn_rechecked_resources_committed"));
    }

    public void provisional(String action, String source, Entity target, String detail, double amount) {
        if (!isEnabled()) return;
        emit(
            s -> MaidTelemetryEvent
                .provisionalDamage(s.epoch, s.sequence, s.tick, action, source, id(target), detail, amount));
    }

    public void nativeCommit(String action, String source, Entity target, String detail, double amount,
        double healthBefore) {
        if (!isEnabled()) return;
        emit(
            s -> MaidTelemetryEvent
                .nativeCommit(s.epoch, s.sequence, s.tick, action, source, id(target), detail, amount, healthBefore));
    }

    public void healthEvidence(String action, String source, Entity target, String detail, EvidenceGrade grade,
        double before, double after, boolean absorptionKnown, double absorptionBefore, double absorptionAfter) {
        if (!isEnabled()) return;
        emit(
            s -> MaidTelemetryEvent.damage(
                s.epoch,
                s.sequence,
                s.tick,
                action,
                source,
                id(target),
                detail,
                grade,
                before,
                after,
                absorptionKnown,
                absorptionBefore,
                absorptionAfter));
    }

    public void terminal(String action, String target, String detail, TerminalOutcome outcome, boolean confirmed) {
        if (!isEnabled()) return;
        emit(s -> MaidTelemetryEvent.terminal(s.epoch, s.sequence, s.tick, action, target, detail, outcome, confirmed));
    }

    private void emit(Function<Stamp, MaidTelemetryEvent> factory) {
        try {
            if (maid.worldObj != recordingWorld || !maid.getUniqueID()
                .equals(recordingMaid)) {
                invalidate("recording_world_or_entity_identity_changed");
                return;
            }
            var stamp = new Stamp(epoch, Math.incrementExact(sequence), recordingWorld.getTotalWorldTime());
            sequence = stamp.sequence;
            var acceptance = ledger.record(factory.apply(stamp));
            if (acceptance != Acceptance.ACCEPTED && acceptance != Acceptance.ACCEPTED_DETAIL_DROPPED)
                invalidate("telemetry_rejected:" + acceptance);
        } catch (RuntimeException | LinkageError failure) {
            invalidate(
                "telemetry_failure:" + failure.getClass()
                    .getSimpleName());
        }
    }

    public static String id(Entity entity) {
        return entity == null ? ""
            : entity.getUniqueID()
                .toString();
    }

    private static final class Stamp {

        final long epoch, sequence, tick;

        Stamp(long epoch, long sequence, long tick) {
            this.epoch = epoch;
            this.sequence = sequence;
            this.tick = tick;
        }
    }
}
