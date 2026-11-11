package com.tianluoqaq.tlmnh.common.ai.perception;

/** 女仆通用感知传感器参数（Tuning） */
public final class MaidPerceptionTuning {

    public static final int SCAN_INTERVAL = 10;
    public static final int SCAN_BUDGET = 32;
    public static final int MAXIMUM_AGE = 20;
    public static final int MAXIMUM_AGE_2 = 40;
    public static final int SCAN_BUDGET_2 = 128;
    public static final int MAX_AGE_THRESHOLD = 40;
    public static final int DANGER_SENSOR_NEXT_SCAN = 0;
    public static final int DANGER_SENSOR_EXPAND_FIRST_PARAM = 8;
    public static final int DANGER_SENSOR_EXPAND_SECOND_PARAM = 4;
    public static final int LIFE_SENSOR_MIN_FIRST_PARAM = 5;
    public static final int LIFE_SENSOR_SCAN_PARAM = 12;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final int CONTAINS_SINGLE_UNIT = 1;
    public static final int LIFE_SENSOR_WITHIN_SEARCH_PARAM = 3;

    private MaidPerceptionTuning() {}
}
