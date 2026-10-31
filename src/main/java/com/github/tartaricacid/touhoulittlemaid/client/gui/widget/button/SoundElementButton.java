/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/SoundElementButton.java, MIT
 * Adaptation: native legacy font and generated builtin event counts
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class SoundElementButton extends FlatColorButton {

    public final String event;
    private final int count;
    private final boolean otherColor;

    public SoundElementButton(int id, int x, int y, String event, int count, boolean otherColor) {
        super(id, x, y, 152, 12, "");
        this.event = event;
        this.count = count;
        this.otherColor = otherColor;
        tooltip = "tooltips.touhou_little_maid.custom_sound.play_sound";
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        background(otherColor ? 0x5F9E9E9E : 0xFF434242, mouseX, mouseY);
        var font = minecraft.fontRenderer;
        int y = yPosition + (height - 8) / 2;
        font.drawStringWithShadow("▷", xPosition + 5, y, 0xE0E0E0);
        font.drawStringWithShadow(
            StatCollector.translateToLocal("button.touhou_little_maid." + event),
            xPosition + 15,
            y,
            0xFAFAFA);
        String counts = count + "♫";
        font.drawStringWithShadow(counts, xPosition + width - font.getStringWidth(counts) - 5, y, 0xCCCCCC);
    }
}
