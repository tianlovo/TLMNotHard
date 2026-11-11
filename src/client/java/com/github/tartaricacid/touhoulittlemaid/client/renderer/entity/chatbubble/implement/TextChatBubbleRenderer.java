/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/chatbubble/implement/TextChatBubbleRenderer.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TextChatBubbleRenderer implements IChatBubbleRenderer {

    private final List<String> split;
    private final int width, height;
    private final ResourceLocation bg;

    public TextChatBubbleRenderer(String text, ResourceLocation bg, Position position) {
        var font = Minecraft.getMinecraft().fontRenderer;
        split = font.listFormattedStringToWidth(text, position == Position.CENTER ? 200 : 120);
        int w = 0;
        for (var line : split) w = Math.max(w, font.getStringWidth(line));
        width = w;
        height = split.size() * font.FONT_HEIGHT;
        this.bg = bg;
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
        int y = 0;
        for (var line : split) {
            graphics.drawString(line, 0, y);
            y += Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT;
        }
    }
}
