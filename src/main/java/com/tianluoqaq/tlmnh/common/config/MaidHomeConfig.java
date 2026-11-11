package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 范围默认值及上下限沿用固定上游，修改后重启 */
public final class MaidHomeConfig {

    public static int workRange = MaidConfigConfigSpec.HOME_CONFIG_WORK_RANGE,
        idleRange = MaidConfigConfigSpec.HOME_CONFIG_IDLE_RANGE,
        sleepRange = MaidConfigConfigSpec.HOME_CONFIG_IDLE_RANGE,
        nonHomeRange = MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM;

    private MaidHomeConfig() {}

    public static void load(Configuration config) {
        workRange = config.getInt(
            MaidConfigConfigSpec.ID_WORKRANGE,
            MaidConfigConfigSpec.ID_HOME,
            MaidConfigConfigSpec.HOME_CONFIG_WORK_RANGE,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FIFTH_PARAM,
            MaidConfigConfigSpec.MSG_HOME_WORK_RADIUS);
        idleRange = config.getInt(
            MaidConfigConfigSpec.ID_IDLERANGE,
            MaidConfigConfigSpec.ID_HOME,
            MaidConfigConfigSpec.HOME_CONFIG_IDLE_RANGE,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FIFTH_PARAM_2,
            MaidConfigConfigSpec.MSG_HOME_IDLE_RADIUS);
        sleepRange = config.getInt(
            MaidConfigConfigSpec.ID_SLEEPRANGE,
            MaidConfigConfigSpec.ID_HOME,
            MaidConfigConfigSpec.HOME_CONFIG_IDLE_RANGE,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FIFTH_PARAM_2,
            MaidConfigConfigSpec.MSG_HOME_SLEEP_RADIUS);
        nonHomeRange = config.getInt(
            MaidConfigConfigSpec.ID_NONHOMERANGE,
            MaidConfigConfigSpec.ID_HOME,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.HOME_CONFIG_GET_INT_FIFTH_PARAM_2,
            MaidConfigConfigSpec.MSG_INITIAL_NON_HOME_RADIUS);
    }
}
