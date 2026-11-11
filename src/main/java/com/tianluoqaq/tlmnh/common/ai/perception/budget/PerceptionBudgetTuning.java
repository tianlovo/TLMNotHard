package com.tianluoqaq.tlmnh.common.ai.perception.budget;

/** 感知扫描限流与帧预算参数（Tuning） */
public final class PerceptionBudgetTuning {

    public static final int ABANDONED_TICKS = 2;
    public static final int MIN_LIMIT_THRESHOLD = 1;
    public static final long SENSING_BUDGET_MERGE_SECOND_LIMIT = 1L;
    public static final int SENSING_BUDGET_USED = 0;
    public static final long SENSING_BUDGET_GET_OR_DEFAULT_SECOND_LIMIT = 0L;

    private PerceptionBudgetTuning() {}
}
