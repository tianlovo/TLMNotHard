/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/MonsterListButton.java, MIT
 * Adaptation: native mouse hit regions and server-snapshot editor copy
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.AttackTaskConfigGui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MonsterListButton extends GuiButton {

    private static final ResourceLocation ICON = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/attack_task_config.png");
    private final AttackTaskConfigGui parent;
    private final ResourceLocation entityId;
    private int part;

    public MonsterListButton(int id, String name, int x, int y, ResourceLocation entityId, AttackTaskConfigGui parent) {
        super(id, x, y, 164, 13, name);
        this.parent = parent;
        this.entityId = entityId;
    }

    private int part(int mouseX, int mouseY) {
        if (mouseY < yPosition || mouseY > yPosition + height) return 0;
        int x = mouseX - xPosition;
        return x >= 107 && x <= 120 ? 1 : x >= 120 && x <= 130 ? 2 : x >= 154 && x <= 164 ? 3 : 0;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        int hover = enabled ? part(mouseX, mouseY) : 0;
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(ICON);
        drawTexturedModalRect(xPosition, yPosition, 0, hover == 1 ? 163 : hover != 0 ? 150 : 137, width, height);
        mc.fontRenderer.drawString(
            mc.fontRenderer.trimStringToWidth(displayString, 99),
            xPosition + 5,
            yPosition + 3,
            enabled ? 0x444444 : 0x888888);
        var type = parent.getAttackGroups()
            .get(entityId);
        if (type != null) drawCenteredString(
            mc.fontRenderer,
            type.getComponent()
                .getFormattedText(),
            xPosition + 142,
            yPosition + 3,
            0xFFFFFF);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        part = part(mouseX, mouseY);
        return enabled && visible && part != 0;
    }

    public void applyClick() {
        parent.edit(entityId, part);
    }
}
