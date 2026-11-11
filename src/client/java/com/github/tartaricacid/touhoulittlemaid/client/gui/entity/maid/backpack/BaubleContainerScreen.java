/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/maid/backpack/BaubleContainerScreen.java, MIT
 * Adaptation: Forge 1.7.10 GUI and favorability-gated inventory
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.backpack;

import java.util.Collections;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.BaubleContainer;
import com.tianluoqaq.tlmnh.client.gui.bauble.MaidBaubleGuiIds;
import com.tianluoqaq.tlmnh.client.gui.bauble.MaidBaubleGuiLayout;
import com.tianluoqaq.tlmnh.common.foundation.ModIds;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class BaubleContainerScreen extends AbstractMaidContainerGui<BaubleContainer> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        ModIds.UPSTREAM_MOD_ID,
        MaidBaubleGuiIds.TEXTURE);

    public BaubleContainerScreen(BaubleContainer container) {
        super(container);
    }

    @Override
    protected boolean isBaublePage() {
        return true;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        mc.getTextureManager()
            .bindTexture(TEXTURE);
        GL11.glColor4f(1, 1, 1, 1);
        drawTexturedModalRect(
            guiLeft + MaidBaubleGuiLayout.PANEL_X,
            guiTop + MaidBaubleGuiLayout.PANEL_Y,
            0,
            0,
            MaidBaubleGuiLayout.PANEL_WIDTH,
            MaidBaubleGuiLayout.PANEL_HEIGHT);
        int level = maid.getFavorabilityManager()
            .getLevel();
        if (level < MaidBaubleLayout.SECOND_TIER_LEVEL)
            drawLockedTier(MaidBaubleGuiLayout.SECOND_TIER_TOP, MaidBaubleGuiLayout.LOCK_Y_OFFSET);
        if (level < MaidBaubleLayout.FINAL_TIER_LEVEL)
            drawLockedTier(MaidBaubleGuiLayout.FINAL_TIER_TOP, MaidBaubleGuiLayout.FINAL_LOCK_Y_OFFSET);
    }

    private void drawLockedTier(int top, int iconOffset) {
        drawRect(
            guiLeft + MaidBaubleGuiLayout.LOCKED_LEFT,
            guiTop + top,
            guiLeft + MaidBaubleGuiLayout.LOCKED_RIGHT,
            guiTop + top + MaidBaubleGuiLayout.LOCKED_HEIGHT,
            MaidBaubleGuiLayout.LOCKED_COLOR);
        mc.getTextureManager()
            .bindTexture(TEXTURE);
        GL11.glColor4f(1, 1, 1, 1);
        drawTexturedModalRect(
            guiLeft + MaidBaubleGuiLayout.LOCK_X,
            guiTop + top + iconOffset,
            MaidBaubleGuiLayout.LOCK_TEXTURE_U,
            0,
            MaidBaubleGuiLayout.LOCK_SIZE,
            MaidBaubleGuiLayout.LOCK_SIZE);
    }

    @Override
    protected void renderAdditionTooltips(int mouseX, int mouseY) {
        int level = maid.getFavorabilityManager()
            .getLevel();
        if (mouseX < guiLeft + MaidBaubleGuiLayout.LOCKED_LEFT || mouseX >= guiLeft + MaidBaubleGuiLayout.LOCKED_RIGHT)
            return;
        int localY = mouseY - guiTop;
        if (level < MaidBaubleLayout.SECOND_TIER_LEVEL && localY >= MaidBaubleGuiLayout.SECOND_TIER_TOP
            && localY < MaidBaubleGuiLayout.SECOND_TIER_TOP + MaidBaubleGuiLayout.LOCKED_HEIGHT)
            showRequiredLevel(mouseX, mouseY, MaidBaubleLayout.SECOND_TIER_LEVEL);
        if (level < MaidBaubleLayout.FINAL_TIER_LEVEL && localY >= MaidBaubleGuiLayout.FINAL_TIER_TOP
            && localY < MaidBaubleGuiLayout.FINAL_TIER_TOP + MaidBaubleGuiLayout.LOCKED_HEIGHT)
            showRequiredLevel(mouseX, mouseY, MaidBaubleLayout.FINAL_TIER_LEVEL);
    }

    private void showRequiredLevel(int mouseX, int mouseY, int level) {
        drawHoveringText(
            Collections.singletonList(StatCollector.translateToLocalFormatted(MaidBaubleGuiIds.REQUIRED_LEVEL, level)),
            mouseX,
            mouseY,
            fontRendererObj);
    }
}
