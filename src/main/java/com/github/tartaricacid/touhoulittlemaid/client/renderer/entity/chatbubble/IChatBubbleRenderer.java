/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/chatbubble/IChatBubbleRenderer.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble;

import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public interface IChatBubbleRenderer {

    int getHeight();

    int getWidth();

    ResourceLocation getBackgroundTexture();

    void render(EntityGraphics graphics);

    enum Position {
        LEFT,
        RIGHT,
        CENTER
    }
}
