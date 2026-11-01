/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/implement/EmojiChatBubbleData.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;

public final class EmojiChatBubbleData implements IChatBubbleData {

    private EmojiChatBubbleData() {}

    public static EmojiChatBubbleData create() {
        return new EmojiChatBubbleData();
    }

    @Override
    public int existTick() {
        return DEFAULT_EXIST_TICK;
    }

    @Override
    public boolean isImage() {
        return true;
    }
}
