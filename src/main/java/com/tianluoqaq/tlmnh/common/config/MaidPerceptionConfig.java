package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 上游弓任务的扫描范围；现代可见记忆仍遵循Sensor默认16格 */
public final class MaidPerceptionConfig {

    public static int bowRange = 48;
    public static int scansPerTick = 8;

    private MaidPerceptionConfig() {}

    public static void load(Configuration config) {
        bowRange = config.getInt("bowRange", "perception", 48, 8, 192, "弓任务实体搜索范围 / Bow-task entity search range");
        scansPerTick = config.getInt(
            "sensingScansPerTick",
            "perception",
            8,
            1,
            256,
            "增强策略每世界tick扫描准入数，非单次耗时上限 / Enhanced scan admissions per world tick, not a time limit");
    }
}
