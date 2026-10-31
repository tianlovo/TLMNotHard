package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 范围默认值及上下限沿用固定上游，修改后重启 */
public final class MaidHomeConfig {

    public static int workRange = 12, idleRange = 6, sleepRange = 6;

    private MaidHomeConfig() {}

    public static void load(Configuration config) {
        workRange = config.getInt("workRange", "home", 12, 3, 64, "家园工作半径 / Home work radius");
        idleRange = config.getInt("idleRange", "home", 6, 3, 32, "家园休闲半径 / Home idle radius");
        sleepRange = config.getInt("sleepRange", "home", 6, 3, 32, "家园睡眠半径 / Home sleep radius");
    }
}
