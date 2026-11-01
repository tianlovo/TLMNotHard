/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/IChatBubbleData.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble;

import net.minecraft.util.ResourceLocation;

public interface IChatBubbleData {

    ResourceLocation TYPE_2 = new ResourceLocation("touhou_little_maid", "textures/entity/chat_bubble/type2.png");
    int DEFAULT_EXIST_TICK = 300;

    int existTick();

    default int priority() {
        return 0;
    }

    default ResourceLocation background() {
        return TYPE_2;
    }

    boolean isImage();

    default String text() {
        return "";
    }
}
