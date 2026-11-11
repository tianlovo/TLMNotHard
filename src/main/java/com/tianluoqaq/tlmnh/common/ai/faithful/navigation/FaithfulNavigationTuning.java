package com.tianluoqaq.tlmnh.common.ai.faithful.navigation;

/** 原版对齐模式路径寻路参数（Tuning） */
public final class FaithfulNavigationTuning {

    public static final int MAX_ATTEMPTS = 10;
    public static final int DOMAIN_RADIUS = 4;
    public static final int WORLD_LIMIT = 29_999_990;
    public static final int DRY_PLATFORM_NEXT_INT_SECOND_PARAM = 7;
    public static final int DRY_PLATFORM_TRY_TELEPORT_X_OFFSET = 3;
    public static final int DRY_PLATFORM_TRY_TELEPORT_PARAM = 2;
    public static final double CENTER_OFFSET_D = 0.5D;
    public static final float TARGET_WIDTH_THRESHOLD = 0.6F;
    public static final float TARGET_HEIGHT_THRESHOLD = 1.5F;
    public static final int MAX_Y_THRESHOLD = 252;
    public static final int MIN_POS_Y_THRESHOLD = 1;
    public static final int MAX_POS_Y_THRESHOLD = 253;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final int DRY_PLATFORM_AREA = 5;

    private FaithfulNavigationTuning() {}
}
