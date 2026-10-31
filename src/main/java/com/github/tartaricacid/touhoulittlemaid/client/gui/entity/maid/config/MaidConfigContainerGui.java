/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/maid/config/MaidConfigContainerGui.java, MIT
 * Adaptation: legacy container, confirmed frequency state and explicit indev pending rows
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config;

import java.util.Collections;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidConfigContainerGui extends AbstractMaidContainerGui<MaidConfigContainer> {

    private static final ResourceLocation ICON = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_gui_config.png");
    private static final String[] KEYS = { "show_backpack", "show_back_item", "show_chat_bubble", "sound_frequency",
        "pick_type", "open_door", "open_fence_gate", "active_climbing" };
    private int frequencyClicks;

    public MaidConfigContainerGui(MaidConfigContainer container) {
        super(container);
    }

    @Override
    protected boolean isConfigPage() {
        return true;
    }

    @Override
    protected void initAdditionWidgets() {
        for (int row = 0; row < KEYS.length; row++) {
            var button = new MaidConfigButton(
                200 + row,
                guiLeft + 86,
                guiTop + 52 + 13 * row,
                StatCollector.translateToLocal("gui.touhou_little_maid.maid_config." + KEYS[row]),
                value(row));
            button.enabled = row == 3;
            buttonList.add(button);
        }
    }

    private String value(int row) {
        return row == 3 ? "§e" + Math.round(
            maid.getConfigManager()
                .getSoundFreq() * 100)
            + "%" : "§7" + StatCollector.translateToLocal("gui.tlmnh.original.config_pending");
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (var raw : buttonList) if (raw instanceof MaidConfigButton button) button.setValue(value(button.id - 200));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof MaidConfigButton setting) {
            if (setting.id != 203 || !setting.enabled
                || mc.currentScreen != this
                || mc.thePlayer.openContainer != inventorySlots
                || !inventorySlots.canInteractWith(mc.thePlayer)
                || mc.thePlayer.inventory.getItemStack() != null) return;
            frequencyClicks++;
            mc.playerController.sendEnchantPacket(
                inventorySlots.windowId,
                setting.isLeftClicked() ? MaidConfigContainer.FREQUENCY_DOWN : MaidConfigContainer.FREQUENCY_UP);
        } else super.actionPerformed(button);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(ICON);
        drawTexturedModalRect(guiLeft + 80, guiTop + 28, 0, 0, xSize, ySize);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.touhou_little_maid.button.maid_config"),
            guiLeft + 167,
            guiTop + 41,
            0xFFFFFF);
        for (var raw : buttonList)
            if (raw instanceof MaidConfigButton setting && !setting.enabled && setting.contains(mouseX, mouseY))
                drawHoveringText(
                    Collections.singletonList(StatCollector.translateToLocal("gui.tlmnh.original.config_pending.desc")),
                    mouseX,
                    mouseY,
                    fontRendererObj);
    }

    /** 开发探针走真实鼠标分发，标题点击不能误当成箭头操作 */
    public boolean requestFrequency(String action) {
        int x = guiLeft + 86 + switch (action) {
            case "config_down" -> 125;
            case "config_up" -> 159;
            case "config_label" -> 40;
            default -> throw new IllegalArgumentException("Unknown frequency action");
        };
        int before = frequencyClicks;
        mouseClicked(x, guiTop + 97, 0);
        mouseMovedOrUp(x, guiTop + 97, 0);
        return frequencyClicks != before;
    }
}
