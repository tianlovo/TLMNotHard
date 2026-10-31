package com.tianluoqaq.tlmnh.client.gui;

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
        GL11.glColor4f(1, 1, 1, 1);
        if (topTab) {
            if (selected) drawTexturedModalRect(xPosition, yPosition, u, 21, width, height);
            drawTexturedModalRect(xPosition + 4, yPosition + 6, u, 47, 16, 16);
        } else if (sideTab) {
            drawTexturedModalRect(xPosition + 6, yPosition + 4, 193, v + 4, 16, 16);
        } else drawTexturedModalRect(
            xPosition,
            yPosition,
            u + (selected ? selectedU : 0),
            v + offsetV + (contains(mouseX, mouseY) || focused ? hoverV : 0),
            width,
            height);
    }
}
