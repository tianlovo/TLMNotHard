package com.tianluoqaq.tlmnh.client.gui.config;

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
    private static int globalMaidSoundFrequency = ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM;
    private static boolean showChatBubbles = true;
    private static final Map<String, Boolean> interactionTips = new HashMap<>();

    private MaidClientPreferences() {}

    public static void load() {
        configuration = new Configuration(new File(Minecraft.getMinecraft().mcDataDir, ClientConfigGuiConfigSpec.ID_CONFIG_TLMNH_CLIENT_CFG));
        configuration.load();
        modelIconCache = configuration.getBoolean(ClientConfigGuiConfigSpec.KEY_ENABLE_MODEL_ICON_CACHE, ClientConfigGuiConfigSpec.ID_MODEL, false, ClientConfigGuiConfigSpec.MSG_CACHE_BUILTIN_MODEL_ICONS);
        globalMaidSoundFrequency = configuration
            .getInt(ClientConfigGuiConfigSpec.KEY_GLOBAL_MAID_SOUND_FREQUENCY, ClientConfigGuiConfigSpec.ID_SOUND, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM, ClientConfigGuiConfigSpec.MSG_GLOBAL_MAID_SOUND_FREQUENCY_PERCENT);
        showChatBubbles = configuration
            .getBoolean(ClientConfigGuiConfigSpec.KEY_GLOBAL_MAID_SHOW_CHAT_BUBBLE, ClientConfigGuiConfigSpec.ID_RENDER, true, ClientConfigGuiConfigSpec.MSG_SHOW_ORDINARY_MAID_CHAT_BUBBLES);
        interactionTips.clear();
        for (var tip : MaidInteractionTipsRules.Tip.values()) interactionTips.put(
            tip.configKey,
            configuration.getBoolean(tip.configKey, ClientConfigGuiConfigSpec.ID_RENDER, true, ClientConfigGuiConfigSpec.MSG_SHOW_THE_MAID_INTERACTION_HINT));
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
        if (percent < 0 || percent > ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM)
            throw new IllegalArgumentException(ClientConfigGuiConfigSpec.MSG_MAID_SOUND_FREQUENCY_PERCENT_OUT);
        if (configuration == null) throw new IllegalStateException(ClientConfigGuiConfigSpec.MSG_CLIENT_PREFERENCES_ARE_NOT_INITIALIZED);
        if (percent == globalMaidSoundFrequency && bubbles == showChatBubbles && cache == modelIconCache) return;
        globalMaidSoundFrequency = percent;
        showChatBubbles = bubbles;
        modelIconCache = cache;
        configuration.get(ClientConfigGuiConfigSpec.ID_SOUND, ClientConfigGuiConfigSpec.KEY_GLOBAL_MAID_SOUND_FREQUENCY, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM)
            .set(percent);
        configuration.get(ClientConfigGuiConfigSpec.ID_RENDER, ClientConfigGuiConfigSpec.KEY_GLOBAL_MAID_SHOW_CHAT_BUBBLE, true)
            .set(bubbles);
        configuration.get(ClientConfigGuiConfigSpec.ID_MODEL, ClientConfigGuiConfigSpec.KEY_ENABLE_MODEL_ICON_CACHE, false)
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
