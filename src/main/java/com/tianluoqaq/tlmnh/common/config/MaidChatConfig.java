package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 上游普通表情默认值，服务端生成开关与客户端显示偏好各自生效 */
public final class MaidChatConfig {

    public static boolean enableEmoji = true;
    public static int checkRate = MaidConfigConfigSpec.CHAT_CONFIG_CHECK_RATE,
        imageWeight = MaidConfigConfigSpec.CHAT_CONFIG_IMAGE_WEIGHT,
        kaomojiWeight = MaidConfigConfigSpec.CHAT_CONFIG_IMAGE_WEIGHT;

    private MaidChatConfig() {}

    public static void load(Configuration config) {
        enableEmoji = config.getBoolean(
            MaidConfigConfigSpec.KEY_ENABLE_EMOJI,
            MaidConfigConfigSpec.ID_CHATBUBBLE,
            true,
            MaidConfigConfigSpec.MSG_ENABLE_ORDINARY_RANDOM_EMOJI);
        checkRate = config.getInt(
            MaidConfigConfigSpec.KEY_EMOJI_CHECK_RATE,
            MaidConfigConfigSpec.ID_CHATBUBBLE,
            MaidConfigConfigSpec.CHAT_CONFIG_CHECK_RATE,
            MaidConfigConfigSpec.CHAT_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.CHAT_CONFIG_GET_INT_FIFTH_PARAM,
            MaidConfigConfigSpec.MSG_TICK_EMOJI_CHECK_INTERVAL_IN);
        imageWeight = config.getInt(
            MaidConfigConfigSpec.KEY_IMAGE_EMOJI_WEIGHT,
            MaidConfigConfigSpec.ID_CHATBUBBLE,
            MaidConfigConfigSpec.CHAT_CONFIG_IMAGE_WEIGHT,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_DEFAULT_ZERO_BASE,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.MSG_IMAGE_EMOJI_WEIGHT);
        kaomojiWeight = config.getInt(
            MaidConfigConfigSpec.KEY_KAOMOJI_EMOJI_WEIGHT,
            MaidConfigConfigSpec.ID_CHATBUBBLE,
            MaidConfigConfigSpec.CHAT_CONFIG_IMAGE_WEIGHT,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_DEFAULT_ZERO_BASE,
            MaidConfigConfigSpec.ACQUISITION_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.MSG_KAOMOJI_WEIGHT_BOTH_ZERO_DISABLES);
    }
}
