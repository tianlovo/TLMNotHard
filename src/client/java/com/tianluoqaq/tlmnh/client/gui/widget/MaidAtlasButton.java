package com.tianluoqaq.tlmnh.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 旧版按钮事件接口与原版TLM图集坐标之间的适配 */
@SideOnly(Side.CLIENT)
public final class MaidAtlasButton extends GuiButton {

    public final String tooltip;
    private final ResourceLocation atlas;
    private final int u, v, hoverV, selectedU;
    public boolean selected;
    public boolean focused;
    public boolean topTab, sideTab;
    public int offsetV;

    public MaidAtlasButton(int id, int x, int y, int width, int height, ResourceLocation atlas, int u, int v,
        int hoverV, int selectedU, String tooltip) {
        super(id, x, y, width, height, "");
        this.atlas = atlas;
        this.u = u;
        this.v = v;
        this.hoverV = hoverV;
        this.selectedU = selectedU;
        this.tooltip = tooltip;
    }

    public boolean contains(int x, int y) {
        return visible && x >= xPosition && y >= yPosition && x < xPosition + width && y < yPosition + height;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        minecraft.getTextureManager()
            .bindTexture(atlas);
        GL11.glColor4f(
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL,
            ClientWidgetLayout.ATLAS_BUTTON_RED_LEVEL);
        if (topTab) {
            if (selected)
                drawTexturedModalRect(xPosition, yPosition, u, ClientWidgetLayout.ATLAS_BUTTON_TEX_V, width, height);
            drawTexturedModalRect(
                xPosition + ClientWidgetLayout.ATLAS_BUTTON_DEST_X,
                yPosition + ClientWidgetLayout.ATLAS_BUTTON_DEST_Y,
                u,
                ClientWidgetLayout.ATLAS_BUTTON_TEX_V_2,
                ClientWidgetLayout.ATLAS_BUTTON_WIDTH,
                ClientWidgetLayout.ATLAS_BUTTON_WIDTH);
        } else if (sideTab) {
            drawTexturedModalRect(
                xPosition + ClientWidgetLayout.ATLAS_BUTTON_DEST_Y,
                yPosition + ClientWidgetLayout.ATLAS_BUTTON_DEST_X,
                ClientWidgetLayout.ATLAS_BUTTON_TEX_U,
                v + ClientWidgetLayout.ATLAS_BUTTON_DEST_X,
                ClientWidgetLayout.ATLAS_BUTTON_WIDTH,
                ClientWidgetLayout.ATLAS_BUTTON_WIDTH);
        } else drawTexturedModalRect(
            xPosition,
            yPosition,
            u + (selected ? selectedU : ClientWidgetLayout.ATLAS_BUTTON_DRAW_BUTTON_ZERO_BASE),
            v + offsetV
                + (contains(mouseX, mouseY) || focused ? hoverV
                    : ClientWidgetLayout.ATLAS_BUTTON_DRAW_BUTTON_ZERO_BASE),
            width,
            height);
    }
}
