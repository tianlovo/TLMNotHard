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

    private MaidClientPreferences() {}

    public static void load() {
        configuration = new Configuration(new File(Minecraft.getMinecraft().mcDataDir, "config/tlmnh-client.cfg"));
        configuration.load();
        modelIconCache = configuration.getBoolean("EnableModelIconCache", "model", false, "Cache builtin model icons");
        if (configuration.hasChanged()) configuration.save();
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
