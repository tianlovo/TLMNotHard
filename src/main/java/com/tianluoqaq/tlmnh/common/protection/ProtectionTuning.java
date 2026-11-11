package com.tianluoqaq.tlmnh.common.protection;

/** 外部兼容指纹校验、账本与熔断守卫常量（Tuning） */
public final class ProtectionTuning {

    public static final int COMPATIBILITY_LEDGER_CHECK_UNIT_STEP = 1;
    public static final int SUBSTRING_START_OFFSET = 0;
    public static final int SHA256_HEX_OF_STREAM_PARAM = 8192;
    public static final int SHA256_HEX_OF_FILE_PARAM = 65536;
    public static final int SHA256_HEX_CHARS = 2;
    public static final int BYTE_BIT_MASK = 0xFF;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final int SHA256_HEX_BIT_MASK_0F = 0x0F;

    private ProtectionTuning() {}
}
