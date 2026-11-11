package com.tianluoqaq.tlmnh.common.development.combat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.Loader;

/** 有界QA会话绑定；没有绑定时生产仍走原规划分支，不保存臂或注入状态 */
public final class CombatEffectPilotSession implements AutoCloseable {

    public enum Arm {
        REACTIVE,
        WEAPON_SELECTION,
        THREAT_ROLLOUTS
    }

    public enum Phase {
        OBSERVATION,
        OFFER,
        SNAPSHOT,
        DECISION,
        SUBMIT
    }

    private static final Map<EntityMaid, CombatEffectPilotSession> ACTIVE = new IdentityHashMap<>();
    private final EntityMaid maid;
    private final World world;
    private final UUID identity;
    private final String run;
    private final Arm arm;
    private final Thread owner = Thread.currentThread();
    private final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
    private final boolean enabledClock;
    private final CombatEffectPilotDecisions simple = new CombatEffectPilotDecisions();
    private final long[] cpu = new long[Phase.values().length], wall = new long[cpu.length],
        calls = new long[cpu.length];
    private final long[] macros = new long[CombatPlan.Macro.values().length],
        results = new long[CombatPlan.Result.values().length];
    private final int[] work = new int[CombatWorkQuota.Work.values().length];
    private long activeAt = Long.MAX_VALUE, decisions, full, forcedWait, workAt = Long.MIN_VALUE;
    private int[] previousWork = new int[work.length];
    private final boolean waitCanary;
    private boolean closed;

    private CombatEffectPilotSession(EntityMaid maid, String run, Arm arm, boolean waitCanary) {
        this.maid = maid;
        world = maid.worldObj;
        identity = maid.getUniqueID();
        this.run = run;
        this.arm = arm;
        this.waitCanary = waitCanary;
        if (!clock.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("Actual server thread CPU unavailable");
        enabledClock = !clock.isThreadCpuTimeEnabled();
        if (enabledClock) clock.setThreadCpuTimeEnabled(true);
        if (clock.getCurrentThreadCpuTime() < 0) throw new IllegalStateException("CPU clock did not enable");
    }

    public static CombatEffectPilotSession bind(EntityMaid maid, String run, Arm arm, boolean waitCanary) {
        gate();
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || maid.worldObj.getEntityByID(maid.getEntityId()) != maid
            || !maid.isUnifiedCombat()
            || !maid.isTamed()
            || maid.getTask() != MaidTask.ATTACK
            || !maid.getBrainCoordinator()
                .isSuspended()
            || maid.getBrainCoordinator()
                .peekEnhancedCore() == null
            || maid.getCombatCadence()
                .hasPersistentState()
            || maid.getCombatHitReceipt()
                .blocksTransfer()
            || run == null
            || run.trim()
                .isEmpty()
            || arm == null
            || !ACTIVE.isEmpty()) throw new IllegalStateException("Fresh exact QA actor and exclusive run required");
        var session = new CombatEffectPilotSession(maid, run, arm, waitCanary);
        ACTIVE.put(maid, session);
        return session;
    }

    public static CombatEffectPilotSession current(EntityMaid maid) {
        if (ACTIVE.isEmpty()) return null;
        var session = ACTIVE.get(maid);
        if (session == null) return null;
        session.check();
        return session;
    }

    public static Measurement measure(EntityMaid maid, Phase phase) {
        var session = current(maid);
        return session == null ? null : session.measure(phase);
    }

    public void activate(long firstActiveTick) {
        check();
        if (activeAt != Long.MAX_VALUE || firstActiveTick <= world.getTotalWorldTime())
            throw new IllegalStateException("Future first full tick required");
        activeAt = firstActiveTick;
    }

    public CombatPlan decide(CombatSnapshot snapshot, CombatWorkQuota quota, CombatPlanner production) {
        check();
        try (var measurement = measure(Phase.DECISION)) {
            CombatPlan plan;
            if (waitCanary && snapshot.tick >= activeAt && snapshot.tick - activeAt < 80) {
                forcedWait++;
                plan = simple.waitPlan(snapshot, quota, "pilot_wait_canary");
            } else if (arm == Arm.THREAT_ROLLOUTS) plan = production.decide(snapshot, quota);
            else plan = simple.decide(snapshot, quota, arm == Arm.WEAPON_SELECTION);
            decisions++;
            if (plan.simulated) full++;
            macros[plan.macro.ordinal()]++;
            account(quota);
            return plan;
        }
    }

    /** A/B不用预演额度，但与C相同地退役本次完整观测准备请求 */
    public boolean completedPreparation(CombatSnapshot snapshot, CombatWorkQuota quota) {
        check();
        return arm != Arm.THREAT_ROLLOUTS && quota.full && snapshot.complete;
    }

    public void accountObservation(CombatWorkQuota quota) {
        check();
        account(quota);
    }

    public void applied(CombatPlan.Result result, CombatWorkQuota quota) {
        check();
        results[result.ordinal()]++;
        account(quota);
    }

    private void account(CombatWorkQuota quota) {
        int[] now = quota.counts();
        if (quota.tick != workAt) {
            workAt = quota.tick;
            previousWork = new int[work.length];
        }
        for (int i = 0; i < now.length; i++) work[i] += Math.max(0, now[i] - previousWork[i]);
        previousWork = now;
    }

    public JsonObject snapshot() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("QA snapshot moved threads");
        var row = new JsonObject();
        row.addProperty("run", run);
        row.addProperty("arm", arm.name());
        row.addProperty("decisions", decisions);
        row.addProperty("fullDecisions", full);
        row.addProperty("forcedWaitDecisions", forcedWait);
        row.addProperty("closed", closed);
        row.addProperty(
            "cpuScope",
            "Exclusive server-thread intervals; submit includes synchronous native callbacks, excludes later arrow entity ticks");
        var phases = new JsonArray();
        for (Phase phase : Phase.values()) {
            var value = new JsonObject();
            int i = phase.ordinal();
            value.addProperty("phase", phase.name());
            value.addProperty("calls", calls[i]);
            value.addProperty("cpuNanos", cpu[i]);
            value.addProperty("wallNanos", wall[i]);
            phases.add(value);
        }
        row.add("phases", phases);
        var actions = new JsonObject();
        for (CombatPlan.Macro macro : CombatPlan.Macro.values())
            actions.addProperty(macro.name(), macros[macro.ordinal()]);
        row.add("macros", actions);
        var outcomes = new JsonObject();
        for (CombatPlan.Result result : CombatPlan.Result.values())
            outcomes.addProperty(result.name(), results[result.ordinal()]);
        row.add("results", outcomes);
        var costs = new JsonObject();
        for (CombatWorkQuota.Work kind : CombatWorkQuota.Work.values())
            costs.addProperty(kind.name(), work[kind.ordinal()]);
        row.add("work", costs);
        return row;
    }

    private Measurement measure(Phase phase) {
        check();
        return new Measurement(phase);
    }

    private void check() {
        gate();
        if (closed || Thread.currentThread() != owner
            || maid.worldObj != world
            || !identity.equals(maid.getUniqueID())
            || ACTIVE.get(maid) != this) throw new IllegalStateException("Expired or foreign combat QA lease");
    }

    private static void gate() {
        if (!DevelopmentFlags.isDevCommandsEnabled() || !Boolean.getBoolean("tlmnh.qaCombatEffectPilot")
            || !Loader.isModLoaded("tlmnhqapilot"))
            throw new IllegalStateException("Explicit combat effect QA mode required");
    }

    @Override
    public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner || ACTIVE.get(maid) != this)
            throw new IllegalStateException("Foreign combat QA cleanup");
        ACTIVE.remove(maid);
        closed = true;
        if (enabledClock) clock.setThreadCpuTimeEnabled(false);
    }

    public final class Measurement implements AutoCloseable {

        private final Phase phase;
        private final long cpuStart = clock.getCurrentThreadCpuTime(), wallStart = System.nanoTime();
        private boolean finished;

        Measurement(Phase phase) {
            this.phase = phase;
        }

        @Override
        public void close() {
            if (finished) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("Combat measurement moved threads");
            long end = clock.getCurrentThreadCpuTime();
            if (cpuStart < 0 || end < cpuStart) throw new IllegalStateException("Combat CPU clock invalidated");
            finished = true;
            int i = phase.ordinal();
            calls[i]++;
            cpu[i] += end - cpuStart;
            wall[i] += System.nanoTime() - wallStart;
        }
    }
}
