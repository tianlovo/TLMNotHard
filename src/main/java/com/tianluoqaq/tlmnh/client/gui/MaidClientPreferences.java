package com.tianluoqaq.tlmnh.client.gui;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 本机客户端偏好，默认值沿用原版 MiscConfig */
@SideOnly(Side.CLIENT)
public final class MaidClientPreferences {

    private static Configuration configuration;
    private static boolean modelIconCache;
    private static int globalMaidSoundFrequency = 100;

    private MaidClientPreferences() {}

    public static void load() {
        configuration = new Configuration(new File(Minecraft.getMinecraft().mcDataDir, "config/tlmnh-client.cfg"));
        configuration.load();
        modelIconCache = configuration.getBoolean("EnableModelIconCache", "model", false, "Cache builtin model icons");
        globalMaidSoundFrequency = configuration
            .getInt("GlobalMaidSoundFrequency", "sound", 100, 0, 100, "Global maid sound frequency percent");
        if (configuration.hasChanged()) configuration.save();
    }

    public static int globalMaidSoundFrequency() {
        return globalMaidSoundFrequency;
    }

    public static void setGlobalMaidSoundFrequency(int percent) {
        if (percent < 0 || percent > 100)
            throw new IllegalArgumentException("Maid sound frequency percent out of range");
        globalMaidSoundFrequency = percent;
        configuration.get("sound", "GlobalMaidSoundFrequency", 100)
            .set(percent);
        configuration.save();
    }

    public static boolean modelIconCache() {
        return modelIconCache;
    }

    public static void setModelIconCache(boolean enabled) {
        modelIconCache = enabled;
        configuration.get("model", "EnableModelIconCache", false)
            .set(enabled);
        configuration.save();
    }
}
