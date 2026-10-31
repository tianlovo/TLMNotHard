package com.tianluoqaq.tlmnh.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 对齐 1.20 Checkbox 的20像素尺寸，背景复用旧版原生控件图集 */
@SideOnly(Side.CLIENT)
public final class MaidCacheCheckbox extends GuiButton {

    private boolean focused;
    private static final int[][] CHECK_STROKES = { { 15, 7, 2, 0xFFFFFFFF }, { 13, 8, 3, 0xFFFFFFFF },
        { 12, 9, 2, 0xFFFFFFFF }, { 14, 9, 1, 0xFFCACACA }, { 4, 10, 1, 0xFFFFFFFF }, { 5, 10, 1, 0xFFCACACA },
        { 11, 10, 2, 0xFFFFFFFF }, { 13, 10, 1, 0xFF868686 }, { 5, 11, 2, 0xFFFFFFFF }, { 7, 11, 1, 0xFFCACACA },
        { 10, 11, 2, 0xFFFFFFFF }, { 12, 11, 1, 0xFF868686 }, { 6, 12, 2, 0xFFFFFFFF }, { 8, 12, 1, 0xFFCACACA },
        { 9, 12, 2, 0xFFFFFFFF }, { 11, 12, 1, 0xFFCACACA }, { 7, 13, 3, 0xFFFFFFFF }, { 10, 13, 1, 0xFFCACACA },
        { 8, 14, 2, 0xFFFFFFFF }, { 10, 14, 1, 0xFF868686 }, { 9, 15, 1, 0xFFCACACA } };

    public MaidCacheCheckbox(int id, int x, int y, String label) {
        super(id, x, y, 20, 20, label);
    }

    public void clearFocus() {
        focused = false;
    }

    @Override
    public boolean mousePressed(Minecraft minecraft, int x, int y) {
        focused = super.mousePressed(minecraft, x, y);
        return focused;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        minecraft.getTextureManager()
            .bindTexture(buttonTextures);
        GL11.glColor4f(1, 1, 1, 1);
        drawRect(xPosition, yPosition, xPosition + 20, yPosition + 20, focused ? 0xFFFFFFFF : 0xFF000000);
        GL11.glColor4f(1, 1, 1, 1);
        drawTexturedModalRect(xPosition + 1, yPosition + 1, 1, 47, 18, 18);
        if (MaidClientPreferences.modelIconCache()) {
            for (var stroke : CHECK_STROKES) drawRect(
                xPosition + stroke[0],
                yPosition + stroke[1],
                xPosition + stroke[0] + stroke[2],
                yPosition + stroke[1] + 1,
                stroke[3]);
        }
        minecraft.fontRenderer.drawStringWithShadow(displayString, xPosition + 24, yPosition + 6, 0xE0E0E0);
    }
}
