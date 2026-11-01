package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 当前原型的寻路调用准入，不能限制单次原生 A* 内部耗时；重启生效 */
public final class MaidNavigationConfig {

    public static int queriesPerTick = 8;

    private MaidNavigationConfig() {}

    public static void load(Configuration config) {
        queriesPerTick = config.getInt(
            "queriesPerWorldTick",
            "navigation",
            8,
            1,
            256,
            "每世界每tick女仆原生寻路调用上限；Indev待实景调优 / Maid path queries per world tick; Indev tuning");
    }
}
