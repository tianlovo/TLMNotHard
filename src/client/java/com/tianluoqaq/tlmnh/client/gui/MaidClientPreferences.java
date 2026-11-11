package com.tianluoqaq.tlmnh.client.gui;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.client.gui.overlay.MaidInteractionTipsRules;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 本机客户端偏好，默认值沿用原版 MiscConfig */
@SideOnly(Side.CLIENT)
public final class MaidClientPreferences {

    private static Configuration configuration;
    private static boolean modelIconCache;
    private static int globalMaidSoundFrequency = 100;
    private static boolean showChatBubbles = true;
    private static final Map<String, Boolean> interactionTips = new HashMap<>();

    private MaidClientPreferences() {}

    public static void load() {
        configuration = new Configuration(new File(Minecraft.getMinecraft().mcDataDir, "config/tlmnh-client.cfg"));
        configuration.load();
        modelIconCache = configuration.getBoolean("EnableModelIconCache", "model", false, "Cache builtin model icons");
        globalMaidSoundFrequency = configuration
            .getInt("GlobalMaidSoundFrequency", "sound", 100, 0, 100, "Global maid sound frequency percent");
        showChatBubbles = configuration
            .getBoolean("GlobalMaidShowChatBubble", "render", true, "显示普通聊天气泡 / Show ordinary maid chat bubbles");
        interactionTips.clear();
        for (var tip : MaidInteractionTipsRules.Tip.values()) interactionTips.put(
            tip.configKey,
            configuration.getBoolean(tip.configKey, "render", true, "Show the maid interaction hint / 显示女仆交互提示"));
        if (configuration.hasChanged()) configuration.save();
    }

    public static boolean interactionTipEnabled(String key) {
        return interactionTips.getOrDefault(key, true);
    }

    public static boolean showChatBubbles() {
        return showChatBubbles;
    }

    public static int globalMaidSoundFrequency() {
        return globalMaidSoundFrequency;
    }

    public static void setGlobalMaidSoundFrequency(int percent) {
        apply(percent, showChatBubbles, modelIconCache);
    }

    /** 全局设置页一次提交本地已有配置，取消或丢失窗口权限不调用这里 */
    public static void apply(int percent, boolean bubbles, boolean cache) {
        if (percent < 0 || percent > 100)
            throw new IllegalArgumentException("Maid sound frequency percent out of range");
        if (configuration == null) throw new IllegalStateException("Client preferences are not initialized");
        if (percent == globalMaidSoundFrequency && bubbles == showChatBubbles && cache == modelIconCache) return;
        globalMaidSoundFrequency = percent;
        showChatBubbles = bubbles;
        modelIconCache = cache;
        configuration.get("sound", "GlobalMaidSoundFrequency", 100)
            .set(percent);
        configuration.get("render", "GlobalMaidShowChatBubble", true)
            .set(bubbles);
        configuration.get("model", "EnableModelIconCache", false)
            .set(cache);
        configuration.save();
    }

    public static boolean modelIconCache() {
        return modelIconCache;
    }

    public static void setModelIconCache(boolean enabled) {
        apply(globalMaidSoundFrequency, showChatBubbles, enabled);
    }
}
