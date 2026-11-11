package com.tianluoqaq.tlmnh.compat.endlessids.common.storage;

/** EndlessIds Storage领域常量定义（Tuning） */
public final class EndlessIdsStorageTuning {

    public static final int MAX_INDEX_THRESHOLD = 4096;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final int UNSIGNED_BYTE_MASK = 255;
    public static final int SECOND_BYTE_SHIFT = 8;
    public static final int ENDLESS_IDS_BIT_SHIFT_12 = 12;
    public static final int THIRD_BYTE_SHIFT = 16;
    public static final int ENDLESS_IDS_ARRAY_THIRD_PARAM = 2048;
    public static final int ENDLESS_IDS_BIT_SHIFT_1 = 1;
    public static final int CHUNK_LOCAL_MASK = 15;

    private EndlessIdsStorageTuning() {}
}
