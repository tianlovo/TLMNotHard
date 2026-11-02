/*
 * Copyright (c) 2019-2025 tartaric_acid, for the code part
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/item/NameTagGui.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: original screen layout with native beacon icons, legacy authoritative container window
 */
package com.tianluoqaq.tlmnh.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.network.MaidNameRequest;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input.LwjglTextInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class GuiMaidNaming extends GuiContainer {

    private static final ResourceLocation TEXTURES = new ResourceLocation("textures/gui/container/beacon.png");
    private final ContainerMaidNaming container;
    private GuiTextField nameField;
    private GuiButton done, cancel, visibility;
    private boolean alwaysShow, pending;
    private int submittedRequests;

    public GuiMaidNaming(ContainerMaidNaming container) {
        super(container);
        this.container = container;
        xSize = 200;
        ySize = 60;
    }

    @Override
    public void initGui() {
        String name = nameField == null ? container.getMaid()
            .getCustomNameTag() : nameField.getText();
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        int middleX = width / 2, middleY = height / 2;
        nameField = new GuiTextField(fontRendererObj, middleX - 99, middleY - 26, 176, 20);
        nameField.setMaxStringLength(MaidNameRules.MAX_LENGTH);
        nameField.setText(name);
        nameField.setFocused(true);
        buttonList.clear();
        visibility = new GuiButton(0, middleX + 80, middleY - 26, 20, 20, "");
        done = new GuiButton(1, middleX - 100, middleY, 98, 20, text("gui.done"));
        cancel = new GuiButton(2, middleX + 2, middleY, 98, 20, text("gui.cancel"));
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

    public ContainerMaidNaming getContainer() {
        return container;
    }

    public boolean submitName(String name, boolean showName) {
        if (pending || mc.currentScreen != this
            || !container.canInteractWith(mc.thePlayer)
            || !MaidNameRules.isValid(name)) return false;
        MaidNamingNetwork.send(MaidNameRequest.from(container, name, showName));
        submittedRequests++;
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
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {}

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            nameField.drawTextBox();
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(TEXTURES);
            drawTexturedModalRect(width / 2 + 80, height / 2 - 26, alwaysShow ? 88 : 110, 220, 20, 20);
            if (mouseX >= visibility.xPosition && mouseX < visibility.xPosition + visibility.width
                && mouseY >= visibility.yPosition
                && mouseY < visibility.yPosition + visibility.height)
                drawCreativeTabHoveringText(text("gui.touhou_little_maid.tag.always_show"), mouseX, mouseY);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void refreshButtons() {
        done.enabled = !pending && MaidNameRules.isValid(nameField.getText());
        visibility.enabled = !pending;
    }

    /** 开发入口复用文本框键入和按钮鼠标分发，不直接改字段或提交协议 */
    public boolean requestAction(String action, String value) {
        if (mc.currentScreen != this || mc.thePlayer.openContainer != container
            || !container.canInteractWith(mc.thePlayer)) return false;
        if (action.equals("naming_text")) {
            if (pending || value == null || value.length() > MaidNameRules.MAX_LENGTH) return false;
            int x = nameField.xPosition + nameField.width / 2, y = nameField.yPosition + nameField.height / 2;
            mouseClicked(x, y, 0);
            mouseMovedOrUp(x, y, 0);
            keyTyped('\0', Keyboard.KEY_HOME);
            int previousLength = nameField.getText()
                .length();
            for (int i = 0; i < previousLength; i++) keyTyped('\0', Keyboard.KEY_DELETE);
            try {
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    LwjglTextInput.character(character);
                    keyTyped(character, Keyboard.KEY_NONE);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native naming GUI text input failed", failure);
            }
            return nameField.getText()
                .equals(value);
        }
        if (action.equals("naming_escape") || action.equals("naming_enter")) {
            keyTyped('\0', action.equals("naming_escape") ? Keyboard.KEY_ESCAPE : Keyboard.KEY_RETURN);
            return true;
        }
        var button = switch (action) {
            case "naming_visibility" -> visibility;
            case "naming_save", "naming_repeat" -> done;
            case "naming_cancel" -> cancel;
            default -> throw new IllegalArgumentException("Unknown naming action");
        };
        if (!button.visible || !button.enabled) return false;
        int x = button.xPosition + button.width / 2, y = button.yPosition + button.height / 2;
        mouseClicked(x, y, 0);
        mouseMovedOrUp(x, y, 0);
        if (action.equals("naming_repeat")) {
            mouseClicked(x, y, 0);
            mouseMovedOrUp(x, y, 0);
        }
        return true;
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("text", nameField.getText());
        result.addProperty("alwaysShow", alwaysShow);
        result.addProperty("pending", pending);
        result.addProperty("submittedRequests", submittedRequests);
        result.addProperty("windowId", container.windowId);
        result.addProperty("containerCurrent", mc.thePlayer.openContainer == container);
        result.addProperty("canInteract", container.canInteractWith(mc.thePlayer));
        result.addProperty("doneEnabled", done.enabled);
        result.addProperty("inputX", nameField.xPosition);
        result.addProperty("inputY", nameField.yPosition);
        result.addProperty("inputWidth", nameField.width);
        result.addProperty("inputHeight", nameField.height);
        result.addProperty("visibilityX", visibility.xPosition);
        result.addProperty("visibilityY", visibility.yPosition);
        result.addProperty("visibilityWidth", visibility.width);
        result.addProperty("visibilityHeight", visibility.height);
        result.addProperty("visibilityTextureU", alwaysShow ? 88 : 110);
        return result;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
