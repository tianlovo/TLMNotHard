package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 上游弓任务的扫描范围；现代可见记忆仍遵循Sensor默认16格 */
public final class MaidPerceptionConfig {

    public static int bowRange = MaidConfigConfigSpec.PERCEPTION_CONFIG_BOW_RANGE;
    public static int scansPerTick = MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM;

    private MaidPerceptionConfig() {}

    public static void load(Configuration config) {
        bowRange = config.getInt(
            MaidConfigConfigSpec.ID_BOWRANGE,
            MaidConfigConfigSpec.ID_PERCEPTION,
            MaidConfigConfigSpec.PERCEPTION_CONFIG_BOW_RANGE,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.PERCEPTION_CONFIG_GET_INT_FIFTH_PARAM,
            MaidConfigConfigSpec.MSG_BOW_TASK_ENTITY_SEARCH_RANGE);
        scansPerTick = config.getInt(
            MaidConfigConfigSpec.ID_SENSINGSCANSPERTICK,
            MaidConfigConfigSpec.ID_PERCEPTION,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
            MaidConfigConfigSpec.NAVIGATION_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.MSG_TICK_ENHANCED_SCAN_ADMISSIONS_PER);
    }
}
