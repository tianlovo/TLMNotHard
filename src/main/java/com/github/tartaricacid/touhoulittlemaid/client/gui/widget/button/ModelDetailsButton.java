/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/ModelDetailsButton.java, MIT
 * Adaptation: native 1.7.10 button and atlas APIs
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ModelDetailsButton extends GuiButton {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/skin_detail.png");
    private final Consumer<Boolean> onClick;
    private boolean selected;

    public ModelDetailsButton(int id, int x, int y, String key, Consumer<Boolean> onClick) {
        super(id, x, y, 128, 12, StatCollector.translateToLocal(key));
        this.onClick = onClick;
    }

    public void press() {
        selected = !selected;
        onClick.accept(selected);
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        minecraft.getTextureManager()
            .bindTexture(TEXTURE);
        GL11.glColor4f(1, 1, 1, 1);
        boolean hover = mouseX >= xPosition && mouseY >= yPosition
            && mouseX < xPosition + width
            && mouseY < yPosition + height;
        drawTexturedModalRect(xPosition, yPosition, selected ? 128 : 0, hover ? 12 : 0, width, height);
        minecraft.fontRenderer.drawString(displayString, xPosition + 14, yPosition + 2, 0xFFFFFFFF);
    }
}
