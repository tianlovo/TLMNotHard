package com.tianluoqaq.tlmnh.common.config;

import java.io.File;
import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

/** 获取途径独立配置；Indev默认值不是最终GTNH配方或生态平衡方案 */
public final class MaidAcquisitionConfig {

    public static boolean naturalSpawning;
    public static boolean giveInitialSlab = true;
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
            true,
            "首次可领取一个绑定魂符，满背包时等待空位 / Give one owner-bound initial soul slab; wait for inventory space");
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
        if (config.hasChanged()) config.save();
    }

    public static boolean allowsDimension(int dimension) {
        return Arrays.stream(naturalDimensions)
            .anyMatch(value -> value == dimension);
    }
}
