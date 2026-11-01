package com.tianluoqaq.tlmnh.common.config;

import java.io.File;
import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

/** 获取途径独立配置；默认蒸汽获取，已有显式配置值保持 */
public final class MaidAcquisitionConfig {

    public static boolean naturalSpawning;
    public static boolean giveInitialSlab;
    public static int naturalWeight = 1;
    public static int[] naturalBiomes = { 1, 4 };
    public static int[] naturalDimensions = { 0 };

    private MaidAcquisitionConfig() {}

    public static void load(File file) {
        var config = new Configuration(file);
        config.load();
        naturalSpawning = config.getBoolean(
            "naturalSpawning",
            "acquisition",
            false,
            "自然生成野生女仆，独立于首次魂符开关；修改后重启 / Natural wild maid spawning; restart required");
        giveInitialSlab = config.getBoolean(
            "giveInitialSlab",
            "acquisition",
            false,
            "可选首次赠送绑定魂符，默认关闭以保留蒸汽获取进度；满背包时等待空位 / Optional free initial owner-bound slab; disabled by default for steam progression; wait for inventory space");
        naturalWeight = config.getInt(
            "naturalWeight",
            "acquisition",
            1,
            1,
            100,
            "自然生成权重，每组1只；Indev默认值待平衡 / Natural spawn weight, one maid per group; Indev balance");
        naturalBiomes = config
            .get("acquisition", "naturalBiomes", new int[] { 1, 4 }, "自然生成群系数字ID / Natural spawning biome IDs")
            .getIntList();
        naturalDimensions = config
            .get("acquisition", "naturalDimensions", new int[] { 0 }, "自然生成维度数字ID / Natural spawning dimension IDs")
            .getIntList();
        MaidHomeConfig.load(config);
        MaidChatConfig.load(config);
        MaidMealConfig.load(config);
        MaidNavigationConfig.load(config);
        MaidPerceptionConfig.load(config);
        MaidTemptationConfig.load(config);
        MaidCombatConfig.load(config);
        if (config.hasChanged()) config.save();
    }

    public static boolean allowsDimension(int dimension) {
        return Arrays.stream(naturalDimensions)
            .anyMatch(value -> value == dimension);
    }
}
