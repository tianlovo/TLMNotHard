package com.tianluoqaq.tlmnh.compat.etfuturum.common.block;

import net.minecraft.block.Block;

/** 只读识别既有方块类型，不主动加载EFR可选类 */
public final class EtFuturumBlocks {

    private EtFuturumBlocks() {}

    public static boolean isBubbleColumn(Block block) {
        return block.getClass()
            .getName()
            .equals(EtFuturumBlockReflectionSpec.TARGET_CLASS_BLOCK_BUBBLE_COLUMN);
    }

    public static boolean isHoney(Block block) {
        return block.getClass()
            .getName()
            .equals(EtFuturumBlockReflectionSpec.TARGET_CLASS_BLOCK_HONEY);
    }

    public static boolean isWitherRose(Block block) {
        return block.getClass()
            .getName()
            .equals(EtFuturumBlockReflectionSpec.TARGET_CLASS_BLOCK_WITHER_ROSE);
    }

    public static boolean isMagma(Block block) {
        return block.getClass()
            .getName()
            .equals(EtFuturumBlockReflectionSpec.TARGET_CLASS_BLOCK_MAGMA);
    }
}
