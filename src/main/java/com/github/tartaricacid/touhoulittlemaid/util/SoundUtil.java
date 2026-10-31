/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: util/SoundUtil.java, MIT
 * Adaptation: native 1.7.10 biome temperature/precipitation and qualified sound names
 */
package com.github.tartaricacid.touhoulittlemaid.util;

import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public final class SoundUtil {

    private static final long MORNING_START = 0, MORNING_END = 3000, EVENING_START = 12000, EVENING_END = 15000;

    private SoundUtil() {}

    public static String environmentSound(EntityMaid maid, String fallback, float probability) {
        var world = maid.worldObj;
        var random = maid.getRNG();
        int x = MathHelper.floor_double(maid.posX), y = MathHelper.floor_double(maid.posY),
            z = MathHelper.floor_double(maid.posZ);
        long time = world.getWorldTime();
        var biome = world.getBiomeGenForCoords(x, z);
        float temperature = biome.getFloatTemperature(x, y, z);
        boolean precipitation = biome.canSpawnLightningBolt() || biome.getEnableSnow();
        // 原版按原始 dayTime 判断，不在移植时改为每日取模；保留各分支先抽随机值
        if (random.nextFloat() < probability && MORNING_START < time && time < MORNING_END)
            return name("environment.morning");
        if (random.nextFloat() < probability && EVENING_START < time && time < EVENING_END)
            return name("environment.night");
        if (random.nextFloat() < probability && world.isRaining()
            && precipitation
            && temperature >= 0.15F
            && temperature <= 1) return name("environment.rain");
        if (random.nextFloat() < probability && world.isRaining() && precipitation && temperature < 0.15F)
            return name("environment.snow");
        if (random.nextFloat() < probability && temperature < 0.15F) return name("environment.cold");
        if (random.nextFloat() < probability && temperature > 1) return name("environment.hot");
        return fallback;
    }

    public static String attackSound(EntityMaid maid, String fallback, float probability) {
        return maid.getRNG()
            .nextFloat() < probability ? name("ai.find_target") : fallback;
    }

    public static String name(String path) {
        return "touhou_little_maid:maid." + path;
    }
}
