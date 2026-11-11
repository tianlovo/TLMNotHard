package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 固定操作额度；共享账本只有计数，不包含世界或可执行查询 */
public final class CombatWorkQuota {

    public enum Work {
        CHUNK,
        ENTITY,
        PROJECTILE,
        BLOCK,
        SHAPE,
        RAY,
        ITEM,
        EVENT,
        CANDIDATE,
        ADVANCE,
        COLLISION,
        FALLBACK,
        FINAL_CHECK,
        BALLISTIC,
        PATH
    }

    // 最终净空、支撑和实体分区检查也收费；LIGHT仍不运行预演
    private static final int[] FULL = { CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_2, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_3, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_5, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7,
        CombatBudgetTuning.MAX_ADVANCES, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_8,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_9, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_5,
        CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM, CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM };
    private static final int[] LIGHT = { CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_10, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_9, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_11,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7,
        CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_12,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_9, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP, CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP };
    private static final int[] PREPARING = { CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_13,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_11, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_9, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_11,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_5, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7,
        CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_12,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_9, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6,
        CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP, CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP };
    static final int[] WORLD = { CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_14,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_15, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_16, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_17,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_18,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_19, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_20,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_16, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_4,
        CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6, CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6 };
    private int[] limit;
    private final int[] used = new int[Work.values().length];
    private final Ledger ledger;
    private final int[] deniedObservation = new int[Work.values().length], denied = new int[Work.values().length];
    public final long tick;
    public boolean full;
    public boolean preparing;
    public long preparationRound;

    CombatWorkQuota(long tick, boolean full, Ledger ledger) {
        this.tick = tick;
        this.full = full;
        this.ledger = ledger;
        limit = (full ? FULL : LIGHT).clone();
    }

    /** 纯规划夹具也使用相同额度，不调用世界调度器 */
    public static CombatWorkQuota isolated(long tick, boolean full) {
        return new CombatWorkQuota(tick, full, new Ledger());
    }

    void upgrade() {
        full = true;
        limit = FULL.clone();
    }

    void prepare(long round) {
        preparing = true;
        preparationRound = round;
        if (!full) limit = PREPARING.clone();
    }

    public boolean spend(Work work) {
        return spend(work, CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP);
    }

    /** 感知不能占用最终空间、可见性和伤害复核所需额度 */
    public boolean spendObservation(Work work) {
        return spendObservation(work, CombatBudgetTuning.COMBAT_RANGE_URGENCY_UNIT_STEP);
    }

    public boolean spendObservation(Work work, int amount) {
        int reserve = observationReserve(work);
        boolean admitted = amount >= 0 && remaining(work) - reserve >= amount && spend(work, amount);
        if (!admitted) deniedObservation[work.ordinal()]++;
        return admitted;
    }

    /** 可选刷新先查看剩余额度，不为探测额度制造观测缺口 */
    public int observationRemaining(Work work) {
        return Math.max(CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED, remaining(work) - observationReserve(work));
    }

    /** 只核原FULL可容纳性；实际重验仍须逐项spend */
    boolean canRefreshFriends(int rays) {
        return canRefreshFriends(ledger, used[Work.RAY.ordinal()], used[Work.ENTITY.ordinal()], rays);
    }

    static boolean canRefreshFriends(Ledger ledger, int rays) {
        return canRefreshFriends(
            ledger,
            CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED,
            CombatBudgetTuning.COMBAT_DIAGNOSTIC_DROPPED,
            rays);
    }

    private static boolean canRefreshFriends(Ledger ledger, int rayUsed, int entityUsed, int rays) {
        return rays >= 0 && canFullObservation(ledger, Work.RAY, rayUsed, rays)
            && canFullObservation(
                ledger,
                Work.ENTITY,
                entityUsed,
                rays * CombatBudgetTuning.COMBAT_RANGE_URGENCY_PARAM);
    }

    private static boolean canFullObservation(Ledger ledger, Work work, int localUsed, int amount) {
        int i = work.ordinal();
        return Math.min(FULL[i] - localUsed, WORLD[i] - ledger.used[i]) - observationReserve(work) >= amount;
    }

    private static int observationReserve(Work work) {
        return switch (work) {
            case CHUNK -> CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_7;
            case ENTITY -> CombatBudgetTuning.COMBAT_WORK_DOMAIN_PARAM_6;
            case RAY -> CombatBudgetTuning.COMBAT_WORK_OBSERVATION_RESERVE_PARAM;
            default -> 0;
        };
    }

    public boolean spend(Work work, int amount) {
        if (amount < 0) throw new IllegalArgumentException(CombatBudgetDiagnostics.MSG_NEGATIVE_COMBAT_WORK);
        int index = work.ordinal();
        if (used[index] > limit[index] - amount || ledger.used[index] > WORLD[index] - amount) {
            denied[index]++;
            return false;
        }
        used[index] += amount;
        ledger.used[index] += amount;
        return true;
    }

    public int used(Work work) {
        return used[work.ordinal()];
    }

    public int remaining(Work work) {
        int index = work.ordinal();
        return Math.min(limit[index] - used[index], WORLD[index] - ledger.used[index]);
    }

    public int[] counts() {
        return used.clone();
    }

    /** 只复制既有计数，诊断不申请额度或访问世界 */
    public int[] observationDenials() {
        return deniedObservation.clone();
    }

    public int[] denials() {
        return denied.clone();
    }

    public static int[] worldLimits() {
        return WORLD.clone();
    }

    static final class Ledger {

        final int[] used = new int[Work.values().length];
    }
}
