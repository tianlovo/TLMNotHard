/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/chatbubble/implement/EmojiChatBubbleRenderer.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.resource.listener.EmojiReloadListener;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EmojiChatBubbleRenderer implements IChatBubbleRenderer {

    private final ResourceLocation bg, emoji;
    private final int width, height;

    public EmojiChatBubbleRenderer(ResourceLocation bg) {
        this.bg = bg;
        var resource = EmojiReloadListener.getRandomEmojis();
        if (resource.isPresent()) {
            var value = resource.get();
            emoji = value.location;
            width = value.width;
            height = value.height;
        } else {
            emoji = new ResourceLocation("touhou_little_maid", "textures/chat_bubble/maid_emoji/emoji_01.png");
            width = 24;
            height = 24;
        }
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public ResourceLocation getBackgroundTexture() {
        return bg;
    }

    @Override
    public void render(EntityGraphics graphics) {
        graphics.blit(emoji, 0, 0, width, height, 0, 0, width, height, width, height);
    }
}
