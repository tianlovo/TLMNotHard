package com.tianluoqaq.tlmnh.common.ai.faithful.perception;

/** 原版对齐模式视线感知与记忆参数（Tuning） */
public final class FaithfulPerceptionTuning {

    public static final int SCAN_RATE = 20;
    public static final int OBSERVATION_TTL = 60;
    public static final int ABSENT_CONTENT_RATE_FIRST_PARAM = 10;
    public static final int ABSENT_CONTENT_RATE_FIRST_PARAM_2 = 50;
    public static final int MODERN_PERCEPTION_NEXT_INT_FIRST_PARAM = 30;
    public static final int MODERN_PERCEPTION_DOMAIN_PARAM = 4;
    public static final int TARGET_GET_ID_THRESHOLD = 3;
    public static final int MODERN_PERCEPTION_VISIBILITY = 1;
    public static final double MODERN_PERCEPTION_TARGETABLE_FACTOR = .8;
    public static final double MODERN_PERCEPTION_TARGETABLE_FACTOR_2 = .7;
    public static final float MODERN_PERCEPTION_MAX_FIRST_FACTOR = .1F;
    public static final float MODERN_PERCEPTION_MAX_SECOND_FACTOR = 4F;
    public static final int MODERN_PERCEPTION_MAX_FIRST_PARAM = 2;
    public static final int MODERN_PERCEPTION_MAX_SECOND_PARAM = 16;

    private FaithfulPerceptionTuning() {}
}
