package com.tianluoqaq.tlmnh.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.gui.config.MaidClientPreferences;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 对齐 1.20 Checkbox 的20像素尺寸，背景复用旧版原生控件图集 */
@SideOnly(Side.CLIENT)
public final class MaidCacheCheckbox extends GuiButton {

    private boolean focused;
    private static final int[][] CHECK_STROKES = {
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_2,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_4, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_5,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_6, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_8,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_9, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_8,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.ATLAS_BUTTON_DEST_X, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_11, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_4, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FF868686 },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_11, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_2, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FF868686 },
        { ClientWidgetLayout.ATLAS_BUTTON_DEST_Y, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_5, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_8, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_12, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_7,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_2, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_4,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_6, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_4,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_5, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_9,
            ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3, ClientWidgetLayout.ARGB_WHITE },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_10, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_9,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FF868686 },
        { ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_8, ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL, ClientWidgetLayout.ARGB_COLOR_FFCACACA } };

    public MaidCacheCheckbox(int id, int x, int y, String label) {
        super(
            id,
            x,
            y,
            ClientWidgetLayout.CACHE_CHECKBOX_MAID_CACHE_CHECKBOX_PARAM,
            ClientWidgetLayout.CACHE_CHECKBOX_MAID_CACHE_CHECKBOX_PARAM,
            label);
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
        GL11.glColor4f(
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL);
        drawRect(
            xPosition,
            yPosition,
            xPosition + ClientWidgetLayout.CACHE_CHECKBOX_MAID_CACHE_CHECKBOX_PARAM,
            yPosition + ClientWidgetLayout.CACHE_CHECKBOX_MAID_CACHE_CHECKBOX_PARAM,
            focused ? ClientWidgetLayout.ARGB_WHITE : ClientWidgetLayout.ARGB_BLACK);
        GL11.glColor4f(
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL);
        drawTexturedModalRect(
            xPosition + 1,
            yPosition + 1,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_TEX_V_2,
            ClientWidgetLayout.CACHE_CHECKBOX_WIDTH,
            ClientWidgetLayout.CACHE_CHECKBOX_WIDTH);
        if (MaidClientPreferences.modelIconCache()) {
            for (var stroke : CHECK_STROKES) drawRect(
                xPosition + stroke[ClientWidgetLayout.ATLAS_BUTTON_DRAW_BUTTON_ZERO_BASE],
                yPosition + stroke[ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL],
                xPosition + stroke[ClientWidgetLayout.ATLAS_BUTTON_DRAW_BUTTON_ZERO_BASE]
                    + stroke[ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_3],
                yPosition + stroke[ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL] + 1,
                stroke[ClientWidgetLayout.CACHE_CHECKBOX_DOMAIN_PARAM_6]);
        }
        minecraft.fontRenderer.drawStringWithShadow(
            displayString,
            xPosition + ClientWidgetLayout.CACHE_CHECKBOX_TEXT_X,
            yPosition + ClientWidgetLayout.ATLAS_BUTTON_DEST_Y,
            ClientWidgetLayout.RGB_COLOR_E0E0E0);
    }
}
