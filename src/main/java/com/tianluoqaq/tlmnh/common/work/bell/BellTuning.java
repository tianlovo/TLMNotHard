package com.tianluoqaq.tlmnh.common.work.bell;

/** 双铃铛堆叠、区块绑定上限、最大切比雪夫跨度与坐标换算参数 */
public final class BellTuning {

    public static final int ZERO = 0;
    public static final int ONE = 1;
    public static final int PAIR_LEN = 2;
    public static final int IDX_X = 0;
    public static final int IDX_Z = 1;
    public static final int MAX_STACK_SIZE = 1;
    public static final int BLOCK_TO_CHUNK_SHIFT = 4;
    public static final int CHUNK_CENTER_OFFSET = 8;
    public static final int MAX_BOUND_CHUNKS = 16;
    public static final int MAX_CHUNK_SPAN = 8;
    public static final int PREVIEW_CHUNKS_NO_SHIFT = 3;
    public static final int PLOT_RADIUS = 1;
    public static final int NBT_COMPOUND_ID = 10;
    public static final int KEY_LSHIFT = 42;
    public static final int KEY_RSHIFT = 54;

    private BellTuning() {}
}
