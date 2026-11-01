/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/chatbubble/EntityGraphics.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EntityGraphics {

    private final int light;

    public EntityGraphics(int light) {
        this.light = light;
    }

    public void drawString(String text, int x, int y) {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        Minecraft.getMinecraft().fontRenderer.drawString(text, x, y, 0xFF000000, false);
    }

    public void blit(ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
        blit(texture, x, y, width, height, u, v, width, height, 256, 256);
    }

    public void blit(ResourceLocation texture, int x, int y, int width, int height, int u, int v, int uWidth,
        int vHeight, int textureWidth, int textureHeight) {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light & 65535, light >> 16 & 65535);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        GL11.glColor4f(1, 1, 1, 1);
        var t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y, 0, (double) u / textureWidth, (double) v / textureHeight);
        t.addVertexWithUV(x, y + height, 0, (double) u / textureWidth, (double) (v + vHeight) / textureHeight);
        t.addVertexWithUV(
            x + width,
            y + height,
            0,
            (double) (u + uWidth) / textureWidth,
            (double) (v + vHeight) / textureHeight);
        t.addVertexWithUV(x + width, y, 0, (double) (u + uWidth) / textureWidth, (double) v / textureHeight);
        t.draw();
    }

    public void blitNineSliced(ResourceLocation texture, int x, int y, int width, int height) {
        int l = Math.min(8, width / 2), rr = l, top = Math.min(8, height / 2), bottom = top;
        int[] xs = { 0, l, width - rr, width }, ys = { 0, top, height - bottom, height };
        int[] us = { 0, l, 48 - rr, 48 }, vs = { 0, top, 24 - bottom, 24 };
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int sw = us[col + 1] - us[col], sh = vs[row + 1] - vs[row];
            int tw = xs[col + 1] - xs[col], th = ys[row + 1] - ys[row];
            for (int dy = 0; dy < th; dy += sh) for (int dx = 0; dx < tw; dx += sw) {
                int w = Math.min(sw, tw - dx), h = Math.min(sh, th - dy);
                blit(texture, x + xs[col] + dx, y + ys[row] + dy, w, h, us[col], vs[row], w, h, 256, 256);
            }
        }
    }
}
