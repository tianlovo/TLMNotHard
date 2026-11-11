/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/FlatColorButton.java, MIT
 * Adaptation: legacy GuiButton event dispatch and native font drawing
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class FlatColorButton extends GuiButton {

    public boolean selected, focused;
    public String tooltip = "";

    public FlatColorButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public boolean contains(int x, int y) {
        return visible && x >= xPosition && x < xPosition + width && y >= yPosition && y < yPosition + height;
    }

    protected void background(int color, int mouseX, int mouseY) {
        drawRect(xPosition, yPosition, xPosition + width, yPosition + height, color);
        if (contains(mouseX, mouseY) || focused) {
            drawRect(xPosition, yPosition + 1, xPosition + 1, yPosition + height - 1, 0xFFF3EFE0);
            drawRect(xPosition, yPosition, xPosition + width, yPosition + 1, 0xFFF3EFE0);
            drawRect(xPosition + width - 1, yPosition + 1, xPosition + width, yPosition + height - 1, 0xFFF3EFE0);
            drawRect(xPosition, yPosition + height - 1, xPosition + width, yPosition + height, 0xFFF3EFE0);
        }
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        background(selected ? 0xFF1E90FF : 0xFF434242, mouseX, mouseY);
        drawCenteredString(
            minecraft.fontRenderer,
            displayString,
            xPosition + width / 2,
            yPosition + (height - 8) / 2,
            0xF3EFE0);
    }
}
