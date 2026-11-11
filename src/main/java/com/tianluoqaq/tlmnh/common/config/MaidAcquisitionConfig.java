package com.tianluoqaq.tlmnh.common.config;

import java.io.File;
import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

/** 获取途径独立配置；默认蒸汽获取，已有显式配置值保持 */
public final class MaidAcquisitionConfig {

    public static boolean naturalSpawning;
    public static boolean giveInitialSlab;
    public static int naturalWeight = MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT;
    public static int[] naturalBiomes = { MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
        MaidConfigConfigSpec.ACQUISITION_CONFIG_DOMAIN_PARAM };
    public static int[] naturalDimensions = { MaidConfigConfigSpec.ACQUISITION_CONFIG_DEFAULT_ZERO_BASE };

    private MaidAcquisitionConfig() {}

    public static void load(File file) {
        var config = new Configuration(file);
        config.load();
        naturalSpawning = config.getBoolean(
            MaidConfigConfigSpec.ID_NATURALSPAWNING,
            MaidConfigConfigSpec.ID_ACQUISITION,
            false,
            MaidConfigConfigSpec.MSG_NATURAL_WILD_MAID_SPAWNING_RESTART);
        giveInitialSlab = config.getBoolean(
            MaidConfigConfigSpec.ID_GIVEINITIALSLAB,
            MaidConfigConfigSpec.ID_ACQUISITION,
            false,
            MaidConfigConfigSpec.MSG_OPTIONAL_FREE_INITIAL_OWNER_BOUND);
        naturalWeight = config.getInt(
            MaidConfigConfigSpec.ID_NATURALWEIGHT,
            MaidConfigConfigSpec.ID_ACQUISITION,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.MSG_INDEV_NATURAL_SPAWN_WEIGHT_ONE);
        naturalBiomes = config
            .get(
                MaidConfigConfigSpec.ID_ACQUISITION,
                MaidConfigConfigSpec.ID_NATURALBIOMES,
                new int[] { MaidConfigConfigSpec.ACQUISITION_CONFIG_NATURAL_WEIGHT,
                    MaidConfigConfigSpec.ACQUISITION_CONFIG_DOMAIN_PARAM },
                MaidConfigConfigSpec.MSG_ID_NATURAL_SPAWNING_BIOME_IDS)
            .getIntList();
        naturalDimensions = config
            .get(
                MaidConfigConfigSpec.ID_ACQUISITION,
                MaidConfigConfigSpec.ID_NATURALDIMENSIONS,
                new int[] { MaidConfigConfigSpec.ACQUISITION_CONFIG_DEFAULT_ZERO_BASE },
                MaidConfigConfigSpec.MSG_ID_NATURAL_SPAWNING_DIMENSION_IDS)
            .getIntList();
        MaidHomeConfig.load(config);
        MaidChatConfig.load(config);
        MaidMealConfig.load(config);
        MaidNavigationConfig.load(config);
        MaidPerceptionConfig.load(config);
        MaidTemptationConfig.load(config);
        MaidCombatConfig.load(config);
        MaidBehaviorConfig.load(config);
        if (config.hasChanged()) config.save();
    }

    public static boolean allowsDimension(int dimension) {
        return Arrays.stream(naturalDimensions)
            .anyMatch(value -> value == dimension);
    }
}
