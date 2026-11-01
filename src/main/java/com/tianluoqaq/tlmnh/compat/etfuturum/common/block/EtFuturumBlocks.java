package com.tianluoqaq.tlmnh.compat.etfuturum.common.block;

import net.minecraft.block.Block;

/** 只读识别既有方块类型，不主动加载EFR可选类 */
public final class EtFuturumBlocks {

    private EtFuturumBlocks() {}

    public static boolean isBubbleColumn(Block block) {
        return block.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.BlockBubbleColumn");
    }

    public static boolean isHoney(Block block) {
        return block.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.BlockHoney");
    }

    public static boolean isWitherRose(Block block) {
        return block.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.BlockWitherRose");
    }

    public static boolean isMagma(Block block) {
        return block.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.BlockMagma");
    }
}
