/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/implement/TextChatBubbleData.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;

public final class TextChatBubbleData implements IChatBubbleData {

    private final String text;
    private final int ticks, priority;

    private TextChatBubbleData(int ticks, String text, int priority) {
        if (ticks < 1 || ticks > 24000 || text == null || text.length() > 256)
            throw new IllegalArgumentException("Invalid core text bubble");
        this.ticks = ticks;
        this.text = text;
        this.priority = priority;
    }

    public static TextChatBubbleData type2(String text) {
        return create(DEFAULT_EXIST_TICK, text, 0);
    }

    public static TextChatBubbleData create(int ticks, String text, int priority) {
        return new TextChatBubbleData(ticks, text, priority);
    }

    @Override
    public int existTick() {
        return ticks;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isImage() {
        return false;
    }

    @Override
    public String text() {
        return text;
    }
}
