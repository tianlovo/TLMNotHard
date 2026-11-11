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

    public static final int MAX_CANDIDATES = 12, HYPOTHESES = 3, HORIZON = 40, STEP = 2;
    public static final int MAX_ADVANCES = MAX_CANDIDATES * HYPOTHESES * HORIZON / STEP;
    // 最终净空、支撑和实体分区检查也收费；LIGHT仍不运行预演
    private static final int[] FULL = { 32, 128, 32, 640, 512, 24, 16, 12, 12, MAX_ADVANCES, 40000, 96, 24, 2, 2 };
    private static final int[] LIGHT = { 16, 48, 16, 96, 64, 12, 16, 12, 12, 0, 2400, 96, 16, 1, 1 };
    private static final int[] PREPARING = { 20, 64, 16, 96, 64, 24, 16, 12, 12, 0, 2400, 96, 16, 1, 1 };
    static final int[] WORLD = { 576, 4096, 512, 3072, 2048, 512, 512, 512, 256, 1440, 90000, 3072, 512, 16, 16 };
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
        return spend(work, 1);
    }

    /** 感知不能占用最终空间、可见性和伤害复核所需额度 */
    public boolean spendObservation(Work work) {
        return spendObservation(work, 1);
    }

    public boolean spendObservation(Work work, int amount) {
        int reserve = observationReserve(work);
        boolean admitted = amount >= 0 && remaining(work) - reserve >= amount && spend(work, amount);
        if (!admitted) deniedObservation[work.ordinal()]++;
        return admitted;
    }

    /** 可选刷新先查看剩余额度，不为探测额度制造观测缺口 */
    public int observationRemaining(Work work) {
        return Math.max(0, remaining(work) - observationReserve(work));
    }

    /** 只核原FULL可容纳性；实际重验仍须逐项spend */
    boolean canRefreshFriends(int rays) {
        return canRefreshFriends(ledger, used[Work.RAY.ordinal()], used[Work.ENTITY.ordinal()], rays);
    }

    static boolean canRefreshFriends(Ledger ledger, int rays) {
        return canRefreshFriends(ledger, 0, 0, rays);
    }

    private static boolean canRefreshFriends(Ledger ledger, int rayUsed, int entityUsed, int rays) {
        return rays >= 0 && canFullObservation(ledger, Work.RAY, rayUsed, rays)
            && canFullObservation(ledger, Work.ENTITY, entityUsed, rays * 2);
    }

    private static boolean canFullObservation(Ledger ledger, Work work, int localUsed, int amount) {
        int i = work.ordinal();
        return Math.min(FULL[i] - localUsed, WORLD[i] - ledger.used[i]) - observationReserve(work) >= amount;
    }

    private static int observationReserve(Work work) {
        return switch (work) {
            case CHUNK -> 12;
            case ENTITY -> 16;
            case RAY -> 7;
            default -> 0;
        };
    }

    public boolean spend(Work work, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative combat work");
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
