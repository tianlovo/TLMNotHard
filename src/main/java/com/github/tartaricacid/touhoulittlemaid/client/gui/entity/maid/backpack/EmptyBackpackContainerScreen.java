/*
 * Ported from TouhouLittleMaid, commit 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original path: client/gui/entity/maid/backpack/EmptyBackpackContainerScreen.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT license
 * Adaptation: Forge 1.7.10 container GUI API
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.backpack;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EmptyBackpackContainerScreen extends AbstractMaidContainerGui<EmptyBackpackContainer> {

    private static final net.minecraft.util.ResourceLocation BACKPACK = new net.minecraft.util.ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_gui_backpack.png");

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        mc.getTextureManager()
            .bindTexture(BACKPACK);
        drawTexturedModalRect(guiLeft + 85, guiTop + 36, 0, 0, 165, 128);
        int[] top = { 58, 81, 122 }, bottom = { 76, 117, 158 }, iconY = { 62, 92, 133 };
        for (int index = 0; index < 3; index++) {
            drawRect(guiLeft + 142, guiTop + top[index], guiLeft + 250, guiTop + bottom[index], 0xaa222222);
            mc.getTextureManager()
                .bindTexture(BACKPACK);
            org.lwjgl.opengl.GL11.glColor4f(1, 1, 1, 1);
            drawTexturedModalRect(guiLeft + 190, guiTop + iconY[index], 165, 0, 11, 11);
        }
        if (!inventorySlots.inventorySlots.get(47)
            .getHasStack()) {
            mc.getTextureManager()
                .bindTexture(
                    new net.minecraft.util.ResourceLocation(
                        "touhou_little_maid",
                        "textures/slot/empty_back_show_slot.png"));
            net.minecraft.client.gui.Gui.func_146110_a(guiLeft + 233, guiTop + 37, 0, 0, 16, 16, 16, 16);
        }
    }

    public EmptyBackpackContainerScreen(EmptyBackpackContainer container) {
        super(container);
    }
}
