/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/chatbubble/RandomEmoji.java, MIT
 * Adaptation: bounded legacy metadata and core text/image bubbles, no LLM
 */
package com.github.tartaricacid.touhoulittlemaid.entity.chatbubble;

import com.github.tartaricacid.touhoulittlemaid.datapack.KaomojiData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.EmojiChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.config.MaidChatConfig;

public final class RandomEmoji {

    private RandomEmoji() {}

    static void tick(EntityMaid maid) {
        if (!MaidChatConfig.enableEmoji) return;
        int rate = MaidChatConfig.checkRate;
        long offset = maid.getUniqueID()
            .getLeastSignificantBits() % rate;
        if ((maid.ticksExisted + offset) % rate != 0) return;
        var manager = maid.getChatBubbleManager();
        if (!manager.getChatBubbleDataCollection()
            .isEmpty()) return;
        int total = MaidChatConfig.imageWeight + MaidChatConfig.kaomojiWeight;
        if (total == 0) return;
        if (maid.getRNG()
            .nextInt(total) < MaidChatConfig.imageWeight) manager.addChatBubble(EmojiChatBubbleData.create());
        else KaomojiData.showRoutineKaomoji(maid, manager);
    }

    public static void addHurtChatText(EntityMaid maid) {
        if (MaidChatConfig.enableEmoji && maid.getChatBubbleManager()
            .getChatBubbleDataCollection()
            .isEmpty()) KaomojiData.showHurtKaomoji(maid, maid.getChatBubbleManager());
    }
}
