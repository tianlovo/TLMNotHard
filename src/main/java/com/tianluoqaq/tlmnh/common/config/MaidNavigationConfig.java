package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 生产原型和增强组件的寻路调用准入，不能限制单次原生 A* 内部耗时；重启生效 */
public final class MaidNavigationConfig {

    public static int queriesPerTick = MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM;

    private MaidNavigationConfig() {}

    public static void load(Configuration config) {
        queriesPerTick = config.getInt(
            MaidConfigConfigSpec.ID_QUERIESPERWORLDTICK,
            MaidConfigConfigSpec.ID_NAVIGATION,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
            MaidConfigConfigSpec.NAVIGATION_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.MSG_TICK_INDEV_MAID_PATH_QUERIES);
    }
}
