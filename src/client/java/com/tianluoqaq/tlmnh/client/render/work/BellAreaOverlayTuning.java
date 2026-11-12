package com.tianluoqaq.tlmnh.client.render.work;

import com.tianluoqaq.tlmnh.common.work.bell.BellTuning;

/** 铃铛范围线框的区块尺寸、颜色与深度偏移 */
public final class BellAreaOverlayTuning {

    public static final int CHUNK_SIZE = 1 << BellTuning.BLOCK_TO_CHUNK_SHIFT;
    public static final int MAINTENANCE_COLOR = 0xC040DCFF;
    public static final int BREEDING_COLOR = 0xD050FF70;
    public static final int CROSS_COLOR = 0xE0FFD040;
    public static final int RED_SHIFT = 16, GREEN_SHIFT = 8, ALPHA_SHIFT = 24;
    public static final int USE_CURRENT_COLOR = -1;
    public static final float LINE_WIDTH = 1.5F;
    public static final double CELL_OUTLINE_HEIGHT = 1.0D;
    public static final double CHUNK_BOTTOM_Y = 0.0D;
    public static final double OUTLINE_OFFSET = 0.015D;

    private BellAreaOverlayTuning() {}
}
