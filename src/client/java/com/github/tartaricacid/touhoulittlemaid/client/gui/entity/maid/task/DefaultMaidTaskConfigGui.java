/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/maid/task/DefaultMaidTaskConfigGui.java, MIT
 * Adaptation: native container, original atlas and retained idle task
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class DefaultMaidTaskConfigGui extends MaidTaskConfigGui {

    private static final ResourceLocation BG = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/default_task_config.png");

    public DefaultMaidTaskConfigGui(TaskConfigContainer container) {
        super(container);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BG);
        drawTexturedModalRect(guiLeft + 80, guiTop + 28, 0, 0, xSize, 137);
    }

    @Override
    protected void renderAddition(float partialTick, int mouseX, int mouseY) {
        fontRendererObj.drawSplitString(
            StatCollector.translateToLocal("gui.touhou_little_maid.default_task_config.title"),
            guiLeft + 88,
            guiTop + 38,
            160,
            0x555555);
    }
}
