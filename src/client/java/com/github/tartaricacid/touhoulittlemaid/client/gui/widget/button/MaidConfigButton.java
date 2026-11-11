/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/MaidConfigButton.java, MIT
 * Adaptation: native legacy button dispatch, original arrow hit boxes and text placement
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidConfigButton extends GuiButton {

    private static final ResourceLocation ICON = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_gui_button.png");
    private String value;
    private boolean leftClicked;

    public MaidConfigButton(int id, int x, int y, String title, String value) {
        super(id, x, y, 164, 13, title);
        this.value = value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isLeftClicked() {
        return leftClicked;
    }

    public boolean contains(int x, int y) {
        return visible && x >= xPosition && x <= xPosition + width && y >= yPosition && y <= yPosition + height;
    }

    @Override
    public boolean mousePressed(Minecraft minecraft, int mouseX, int mouseY) {
        if (!enabled || !contains(mouseX, mouseY)) return false;
        if (mouseX >= xPosition + 120 && mouseX <= xPosition + 130) {
            leftClicked = true;
            return true;
        }
        if (mouseX >= xPosition + 154 && mouseX <= xPosition + 164) {
            leftClicked = false;
            return true;
        }
        return false;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1, 1, 1, 1);
        minecraft.getTextureManager()
            .bindTexture(ICON);
        drawTexturedModalRect(xPosition, yPosition, 63, contains(mouseX, mouseY) ? 141 : 128, width, height);
        var font = minecraft.fontRenderer;
        font.drawString(displayString, xPosition + 5, yPosition + 3, 0x444444);
        font.drawString(value, xPosition + 142 - font.getStringWidth(value) / 2, yPosition + 3, 0x55FF55);
    }
}
