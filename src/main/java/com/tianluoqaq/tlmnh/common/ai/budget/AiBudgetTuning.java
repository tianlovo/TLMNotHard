package com.tianluoqaq.tlmnh.common.ai.budget;

/** AI 帧预算与工作配额参数（Tuning） */
public final class AiBudgetTuning {

    public static final int ABANDONED_TICKS = 2;
    public static final int PROJECTILE_LIMIT = 256;
    public static final int MIN_LIMIT_THRESHOLD = 1;
    public static final int NAVIGATION_BUDGET_USED = 0;
    public static final int PATH_QUERY_PAD = 8;
    public static final int TARGET_RESULT_THRESHOLD = 2;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double PATH_QUERY_BOX = 0.2;
    public static final double PATH_QUERY_BOX_2 = 0.8;

    private AiBudgetTuning() {}
}
