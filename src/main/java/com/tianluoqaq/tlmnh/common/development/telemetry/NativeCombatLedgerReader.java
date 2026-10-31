package com.tianluoqaq.tlmnh.common.development.telemetry;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.RandomAccess;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.CombatEntry;
import net.minecraft.util.CombatTracker;
import net.minecraft.util.DamageSource;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** 只读原生提交账本；amount不是healthAfter，clamp值也只代表原生公式下的估计 */
public final class NativeCombatLedgerReader {

    public enum ResetReason {
        NONE,
        DEATH_PHASE,
        UNEXPLAINED
    }

    private static final int READ_LIMIT = 64, IDENTITY_LIMIT = 128;
    private static final Field ENTRIES = field(CombatTracker.class, "combatEntries", "field_94556_a");
    private static final Field HEALTH_BEFORE = field(CombatEntry.class, "field_94565_d");
    private static final Field VICTIM_TICK = field(CombatEntry.class, "field_94567_b");

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

        private Cursor(EntityLivingBase victim) {
            if (victim == null) throw new IllegalArgumentException("Victim is required");
            this.victim = victim;
            tracker = victim.func_110142_aN();
            ledger = entries(tracker);
            int size = ledger.size();
            for (int i = Math.max(0, size - READ_LIMIT); i < size; i++) remember(checked(ledger.get(i)));
            previousLast = size == 0 ? null : checked(ledger.get(size - 1));
        }

        /** 伤害入口前、死亡回调中和服务tick末采集；旧条目只作watermark，不追认会话前伤害 */
        public Harvest harvest(boolean deathPhase) {
            if (victim.func_110142_aN() != tracker || entries(tracker) != ledger)
                throw new IllegalStateException("Native combat tracker or ledger was replaced");
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
            boolean expectedReset = resetObserved && (deathPhase || expectedDeathClear);
            // 短表的reset也公开报告；不能从轮询推断任意第三方清表期间没有漏项
            previousLast = size == 0 ? null : checked(ledger.get(size - 1));
            if (size == 0) {
                seen.clear();
                identityOrder.clear();
            }
            expectedDeathClear = deathPhase;
            return new Harvest(fresh, size, size - from, resetObserved, expectedReset, tailIncomplete);
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

        private Harvest(List<Entry> entries, int size, int inspected, boolean reset, boolean expected,
            boolean incomplete) {
            this.entries = Collections.unmodifiableList(entries);
            nativeSize = size;
            this.inspected = inspected;
            resetObserved = reset;
            resetWasExpected = expected;
            tailIncomplete = incomplete;
            resetReason = !reset ? ResetReason.NONE : expected ? ResetReason.DEATH_PHASE : ResetReason.UNEXPLAINED;
        }
    }
}
