/*
 * Ported from TouhouLittleMaid, commit 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original path: client/gui/entity/maid/backpack/EmptyBackpackContainerScreen.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT license
 * Adaptation: Forge 1.7.10 container GUI API
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.backpack;

import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class EmptyBackpackContainerScreen extends AbstractMaidContainerGui<EmptyBackpackContainer> {

    private static final ResourceLocation BACKPACK = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_gui_backpack.png");

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        mc.getTextureManager()
            .bindTexture(BACKPACK);
        drawTexturedModalRect(guiLeft + 85, guiTop + 36, 0, 0, 165, 128);
        if (!inventorySlots.inventorySlots.get(47)
            .getHasStack()) {
            mc.getTextureManager()
                .bindTexture(new ResourceLocation("touhou_little_maid", "textures/slot/empty_back_show_slot.png"));
            Gui.func_146110_a(guiLeft + 233, guiTop + 37, 0, 0, 16, 16, 16, 16);
        }
    }

    public EmptyBackpackContainerScreen(EmptyBackpackContainer container) {
        super(container);
    }
}
