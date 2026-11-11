package com.tianluoqaq.tlmnh.common.lifecycle.spi;

/** 生命周期兼容扩展接口常量（Tuning） */
public final class LifecycleSpiTuning {

    public static final int UNSIGNED_BYTE_MASK = 255;
    public static final int LIFECYCLE_STORAGE_BIT_SHIFT_1 = 1;
    public static final int LIFECYCLE_STORAGE_SECTION_BLOCK_ID_PARAM = 4;
    public static final int CHUNK_LOCAL_MASK = 15;
    public static final int SECOND_BYTE_SHIFT = 8;
    public static final int MAX_INDEX_THRESHOLD = 4096;
    public static final int TARGET_LENGTH_THRESHOLD = 2048;

    private LifecycleSpiTuning() {}
}
