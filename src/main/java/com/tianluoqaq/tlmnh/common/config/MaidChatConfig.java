package com.tianluoqaq.tlmnh.common.config;

import net.minecraftforge.common.config.Configuration;

/** 上游普通表情默认值，服务端生成开关与客户端显示偏好各自生效 */
public final class MaidChatConfig {

    public static boolean enableEmoji = true;
    public static int checkRate = 1200, imageWeight = 10, kaomojiWeight = 10;

    private MaidChatConfig() {}

    public static void load(Configuration config) {
        enableEmoji = config.getBoolean("EnableEmoji", "chatBubble", true, "启用普通随机表情 / Enable ordinary random emoji");
        checkRate = config
            .getInt("EmojiCheckRate", "chatBubble", 1200, 20, 24000, "随机表情检查间隔，tick / Emoji check interval in ticks");
        imageWeight = config.getInt("ImageEmojiWeight", "chatBubble", 10, 0, 100, "图片表情权重 / Image emoji weight");
        kaomojiWeight = config.getInt(
            "KaomojiEmojiWeight",
            "chatBubble",
            10,
            0,
            100,
            "颜文字权重；两者均为0时不生成 / Kaomoji weight; both zero disables generation");
    }
}
