package com.tianluoqaq.tlmnh.common.development.telemetry;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.CombatEntry;
import net.minecraft.util.CombatTracker;
import net.minecraft.util.DamageSource;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** 只读原生提交账本；amount不是healthAfter，clamp值也只代表原生公式下的估计 */
public final class NativeCombatLedgerReader {

    public enum ResetReason {
        NONE,
        DEATH_PHASE,
        NATIVE_TIMEOUT,
        UNEXPLAINED
    }

    private static final int READ_LIMIT = 64, IDENTITY_LIMIT = 128;
    // CombatTracker.func_94549_h and EntityLivingBase.onUpdate own these native accounting rules
    private static final int COMBAT_TIMEOUT_TICKS = 300, NONCOMBAT_TIMEOUT_TICKS = 100, MAINTENANCE_INTERVAL_TICKS = 20;
    private static final Field ENTRIES = field(CombatTracker.class, "combatEntries", "field_94556_a");
    private static final Field HEALTH_BEFORE = field(CombatEntry.class, "field_94565_d");
    private static final Field VICTIM_TICK = field(CombatEntry.class, "field_94567_b");
    private static final Field LAST_HIT = field(CombatTracker.class, "field_94555_c");
    private static final Field COMBAT_START = field(CombatTracker.class, "field_152775_d");
    private static final Field COMBAT_END = field(CombatTracker.class, "field_152776_e");
    private static final Field IN_COMBAT = field(CombatTracker.class, "field_94552_d");
    private static final Field TRACKING_DAMAGE = field(CombatTracker.class, "field_94553_e");

    private NativeCombatLedgerReader() {}

    public static Cursor watch(EntityLivingBase victim) {
        return new Cursor(victim);
    }

    private static Field field(Class<?> owner, String... names) {
        return ReflectionHelper.findField(owner, names);
    }

    @SuppressWarnings("unchecked")
    private static List<CombatEntry> entries(CombatTracker tracker) {
        try {
            Object value = ENTRIES.get(tracker);
            if (!(value instanceof List<?>) || !(value instanceof RandomAccess))
                throw new IllegalStateException("Unsupported native combat ledger container");
            return (List<CombatEntry>) value;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native combat ledger is not readable", failure);
        }
    }

    public static final class Cursor {

        private final EntityLivingBase victim;
        private final CombatTracker tracker;
        private final List<CombatEntry> ledger;
        private final IdentityHashMap<CombatEntry, Boolean> seen = new IdentityHashMap<>();
        private final ArrayDeque<CombatEntry> identityOrder = new ArrayDeque<>();
        private CombatEntry previousLast;
        private boolean expectedDeathClear;
        private NativeState previousState;
        private int previousSize;
        private boolean previousSampleComplete;

        private Cursor(EntityLivingBase victim) {
            if (victim == null) throw new IllegalArgumentException("Victim is required");
            this.victim = victim;
            tracker = victim.func_110142_aN();
            ledger = entries(tracker);
            int size = ledger.size();
            for (int i = Math.max(0, size - READ_LIMIT); i < size; i++) remember(checked(ledger.get(i)));
            previousLast = size == 0 ? null : checked(ledger.get(size - 1));
            previousState = state(victim, tracker);
            previousSize = size;
            // A later complete harvest must establish continuity before a timeout can be explained
            previousSampleComplete = size == 0;
        }

        /** 伤害入口前、死亡回调中和服务tick末采集；旧条目只作watermark，不追认会话前伤害 */
        public Harvest harvest(boolean deathPhase) {
            if (victim.func_110142_aN() != tracker || entries(tracker) != ledger)
                throw new IllegalStateException("Native combat tracker or ledger was replaced");
            NativeState current = state(victim, tracker), prior = previousState;
            int priorSize = previousSize;
            boolean priorComplete = previousSampleComplete;
            int priorLastTick = previousLast == null ? -1 : victimTick(previousLast);
            int size = ledger.size(), from = Math.max(0, size - READ_LIMIT);
            var fresh = new ArrayList<Entry>();
            boolean anchorPresent = false;
            for (int i = from; i < size; i++) {
                CombatEntry item = checked(ledger.get(i));
                if (item == previousLast) anchorPresent = true;
                if (!seen.containsKey(item)) {
                    fresh.add(read(item));
                    remember(item);
                }
            }
            boolean resetObserved = previousLast != null && !anchorPresent;
            boolean tailIncomplete = !anchorPresent && size > READ_LIMIT;
            // lethal提交会先因health=0清旧表，再在本次death回调前添加最终条目
            // 上一次death回调后的下一次harvest也可能才见到原生onDeath清表
            boolean deathReset = resetObserved && (deathPhase || expectedDeathClear);
            boolean timeoutReset = resetObserved && !deathReset
                && nativeTimeout(prior, current, priorSize, priorComplete, priorLastTick, size, fresh, tailIncomplete);
            ResetReason reason = !resetObserved ? ResetReason.NONE
                : deathReset ? ResetReason.DEATH_PHASE
                    : timeoutReset ? ResetReason.NATIVE_TIMEOUT : ResetReason.UNEXPLAINED;
            boolean expectedReset = deathReset || timeoutReset;
            // 短表的reset也公开报告；不能从轮询推断任意第三方清表期间没有漏项
            previousLast = size == 0 ? null : checked(ledger.get(size - 1));
            if (size == 0) {
                seen.clear();
                identityOrder.clear();
            }
            expectedDeathClear = deathPhase;
            previousState = current;
            previousSize = size;
            previousSampleComplete = size <= READ_LIMIT && !tailIncomplete && (!resetObserved || expectedReset);
            return new Harvest(
                fresh,
                size,
                size - from,
                resetObserved,
                expectedReset,
                tailIncomplete,
                reason,
                prior,
                current,
                priorSize,
                priorComplete,
                priorLastTick);
        }

        private boolean nativeTimeout(NativeState before, NativeState after, int priorSize, boolean priorComplete,
            int priorLastTick, int size, List<Entry> fresh, boolean incomplete) {
            if (before == null || !before.alive
                || !after.alive
                || !priorComplete
                || priorSize <= 0
                || priorSize > READ_LIMIT
                || previousLast == null
                || !seen.containsKey(previousLast)
                || size != 0
                || !fresh.isEmpty()
                || incomplete) return false;
            int clearTick = after.combatEndTick;
            boolean worldUpdateEndpoint = clearTick == after.victimTick;
            // The native MP full update can run before World increments its entity tick
            boolean nativePlayerUpdateEndpoint = victim.getClass() == EntityPlayerMP.class
                && clearTick == before.victimTick;
            if ((long) after.victimTick - before.victimTick != 1
                || (!worldUpdateEndpoint && !nativePlayerUpdateEndpoint)
                || clearTick % MAINTENANCE_INTERVAL_TICKS != 0
                || !before.trackingDamage
                || before.lastHitTick != priorLastTick
                || before.combatStartTick > before.lastHitTick
                || before.combatEndTick > before.lastHitTick
                || (before.inCombat && before.combatEndTick != before.combatStartTick)
                || after.lastHitTick != before.lastHitTick
                || after.trackingDamage
                || after.inCombat
                || before.combatEndTick >= after.combatEndTick
                || after.combatStartTick != before.combatStartTick) return false;
            int timeout = before.inCombat ? COMBAT_TIMEOUT_TICKS : NONCOMBAT_TIMEOUT_TICKS;
            long age = (long) clearTick - after.lastHitTick;
            // Only the first eligible native periodic check is classified, never an arbitrary later clear
            return before.lastHitTick >= 0 && before.lastHitTick <= before.victimTick
                && age > timeout
                && age - MAINTENANCE_INTERVAL_TICKS <= timeout;
        }

        private void remember(CombatEntry item) {
            if (seen.put(item, Boolean.TRUE) != null) return;
            identityOrder.addLast(item);
            if (identityOrder.size() > IDENTITY_LIMIT) seen.remove(identityOrder.removeFirst());
        }
    }

    private static CombatEntry checked(Object value) {
        if (!(value instanceof CombatEntry entry)) throw new IllegalStateException("Unknown combat ledger entry");
        return entry;
    }

    private static int victimTick(CombatEntry entry) {
        try {
            return VICTIM_TICK.getInt(entry);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native combat entry tick is not readable", failure);
        }
    }

    private static NativeState state(EntityLivingBase victim, CombatTracker tracker) {
        try {
            return new NativeState(
                victim.ticksExisted,
                victim.isEntityAlive(),
                LAST_HIT.getInt(tracker),
                COMBAT_START.getInt(tracker),
                COMBAT_END.getInt(tracker),
                IN_COMBAT.getBoolean(tracker),
                TRACKING_DAMAGE.getBoolean(tracker));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native combat accounting state is not readable", failure);
        }
    }

    /** 纯值原生计账快照，仅供开发观测，不参与敌方AI或战术决策 */
    public static final class NativeState {

        public final int victimTick, lastHitTick, combatStartTick, combatEndTick;
        public final boolean alive, inCombat, trackingDamage;

        private NativeState(int tick, boolean alive, int lastHit, int combatStart, int combatEnd, boolean inCombat,
            boolean trackingDamage) {
            victimTick = tick;
            this.alive = alive;
            lastHitTick = lastHit;
            combatStartTick = combatStart;
            combatEndTick = combatEnd;
            this.inCombat = inCombat;
            this.trackingDamage = trackingDamage;
        }
    }

    private static Entry read(CombatEntry entry) {
        try {
            float before = HEALTH_BEFORE.getFloat(entry), amount = entry.func_94563_c();
            DamageSource source = entry.getDamageSrc();
            if (source == null || !Float.isFinite(before) || !Float.isFinite(amount) || before < 0 || amount < 0)
                throw new IllegalStateException("Invalid native combat ledger value");
            return new Entry(source, VICTIM_TICK.getInt(entry), before, amount);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native combat entry is not readable", failure);
        }
    }

    public static final class Entry {

        public final DamageSource source;
        public final int victimTick;
        public final double healthBefore, nativeRecordedDamage, clampedNativeLossEstimate;

        private Entry(DamageSource source, int victimTick, float healthBefore, float amount) {
            this.source = source;
            this.victimTick = victimTick;
            this.healthBefore = healthBefore;
            nativeRecordedDamage = amount;
            clampedNativeLossEstimate = Math.min(healthBefore, amount);
        }
    }

    public static final class Harvest {

        public final List<Entry> entries;
        public final int nativeSize, inspected;
        public final boolean resetObserved, resetWasExpected, tailIncomplete;
        public final ResetReason resetReason;
        public final NativeState previousSample, currentSample;
        public final int previousNativeSize, previousLastEntryVictimTick;
        public final boolean previousSampleComplete;

        private Harvest(List<Entry> entries, int size, int inspected, boolean reset, boolean expected,
            boolean incomplete, ResetReason reason, NativeState previous, NativeState current, int previousSize,
            boolean previousComplete, int previousLastTick) {
            this.entries = Collections.unmodifiableList(entries);
            nativeSize = size;
            this.inspected = inspected;
            resetObserved = reset;
            resetWasExpected = expected;
            tailIncomplete = incomplete;
            resetReason = reason;
            previousSample = previous;
            currentSample = current;
            previousNativeSize = previousSize;
            previousSampleComplete = previousComplete;
            previousLastEntryVictimTick = previousLastTick;
        }
    }
}
