/*
 * Copyright (c) 2019-2025 tartaric_acid, for the code part
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/item/NameTagGui.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 container window and server-authoritative name request
 */
package com.tianluoqaq.tlmnh.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.network.MaidNameRequest;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class GuiMaidNaming extends GuiContainer {

    private final ContainerMaidNaming container;
    private GuiTextField nameField;
    private GuiButton done, cancel, visibility;
    private boolean alwaysShow, pending;

    public GuiMaidNaming(ContainerMaidNaming container) {
        super(container);
        this.container = container;
        xSize = 280;
        ySize = 144;
    }

    @Override
    public void initGui() {
        String name = nameField == null ? container.getMaid()
            .getCustomNameTag() : nameField.getText();
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        nameField = new GuiTextField(fontRendererObj, guiLeft + 12, guiTop + 38, 256, 20);
        nameField.setMaxStringLength(MaidNameRules.MAX_LENGTH);
        nameField.setText(name);
        nameField.setFocused(true);
        buttonList.clear();
        visibility = new GuiButton(0, guiLeft + 12, guiTop + 66, 256, 20, "");
        done = new GuiButton(1, guiLeft + 12, guiTop + 112, 124, 20, text("gui.done"));
        cancel = new GuiButton(2, guiLeft + 144, guiTop + 112, 124, 20, text("gui.cancel"));
        buttonList.add(visibility);
        buttonList.add(done);
        buttonList.add(cancel);
        refreshButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!container.canInteractWith(mc.thePlayer)) {
            mc.thePlayer.closeScreen();
            return;
        }
        nameField.updateCursorCounter();
        refreshButtons();
    }

    public EntityMaid getMaid() {
        return container.getMaid();
    }

    public boolean submitName(String name, boolean showName) {
        if (pending || mc.currentScreen != this
            || !container.canInteractWith(mc.thePlayer)
            || !MaidNameRules.isValid(name)) return false;
        MaidNamingNetwork.send(MaidNameRequest.from(container, name, showName));
        pending = true;
        refreshButtons();
        return true;
    }

    public void cancelNaming() {
        if (mc.currentScreen == this) mc.thePlayer.closeScreen();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        if (button == done) submitName(nameField.getText(), alwaysShow);
        else if (button == cancel) cancelNaming();
        else if (button == visibility) {
            alwaysShow = !alwaysShow;
            refreshButtons();
        }
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) cancelNaming();
        else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
            submitName(nameField.getText(), alwaysShow);
        else if (!pending && nameField.textboxKeyTyped(character, keyCode)) refreshButtons();
        else if (!nameField.isFocused()) super.keyTyped(character, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (!pending) nameField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xEF242424);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(text("gui.tlmnh.naming.title"), 12, 10, 0xFFFFFF);
        fontRendererObj.drawString(text("gui.tlmnh.naming.limit"), 12, 24, 0xBBBBBB);
        String status = pending ? "gui.tlmnh.naming.pending" : "gui.tlmnh.naming.cost";
        fontRendererObj.drawString(text(status), 12, 94, 0xBBBBBB);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            nameField.drawTextBox();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void refreshButtons() {
        done.enabled = !pending && MaidNameRules.isValid(nameField.getText());
        visibility.enabled = !pending;
        visibility.displayString = text(alwaysShow ? "gui.tlmnh.naming.always_show" : "gui.tlmnh.naming.normal_show");
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
