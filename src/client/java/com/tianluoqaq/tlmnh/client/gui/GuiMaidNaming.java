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
import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractionTuning;
import com.tianluoqaq.tlmnh.common.interaction.MaidNameRules;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.network.MaidNameRequest;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class GuiMaidNaming extends GuiContainer {

    private static final ResourceLocation TEXTURES = new ResourceLocation(ClientGuiIds.TEXTURE_BEACON);
    private final ContainerMaidNaming container;
    private GuiTextField nameField;
    private GuiButton done, cancel, visibility;
    private boolean alwaysShow, pending;
    private int submittedRequests;

    public GuiMaidNaming(ContainerMaidNaming container) {
        super(container);
        this.container = container;
        xSize = ClientGuiLayout.GUI_NAMING_X_SIZE;
        ySize = ClientGuiLayout.GUI_NAMING_Y_SIZE;
    }

    @Override
    public void initGui() {
        String name = nameField == null ? container.getMaid()
            .getCustomNameTag() : nameField.getText();
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        int middleX = width / ClientGuiLayout.MIDPOINT_DIVISOR, middleY = height / ClientGuiLayout.MIDPOINT_DIVISOR;
        nameField = new GuiTextField(
            fontRendererObj,
            middleX - ClientGuiLayout.GUI_NAMING_NAME_FIELD,
            middleY - ClientGuiLayout.FILTER_BTN_Y,
            ClientGuiLayout.GUI_RECT_LEFT,
            ClientGuiLayout.ACTION_BTN_HEIGHT);
        nameField.setMaxStringLength(MaidInteractionTuning.MAX_LENGTH);
        nameField.setText(name);
        nameField.setFocused(true);
        buttonList.clear();
        visibility = new GuiButton(
            ClientGuiLayout.GUI_MODELS_PAGE,
            middleX + ClientGuiLayout.GUI_NAMING_VISIBILITY,
            middleY - ClientGuiLayout.FILTER_BTN_Y,
            ClientGuiLayout.ACTION_BTN_HEIGHT,
            ClientGuiLayout.ACTION_BTN_HEIGHT,
            "");
        done = new GuiButton(
            ClientGuiLayout.SUBSTRING_START_OFFSET,
            middleX - ClientGuiLayout.PICKUP_ITEMS_BTN_Y,
            middleY,
            ClientGuiLayout.GUI_NAMING_BTN_WIDTH,
            ClientGuiLayout.ACTION_BTN_HEIGHT,
            text(ClientGuiIds.I18N_GUI_DONE));
        cancel = new GuiButton(
            ClientGuiLayout.MIDPOINT_DIVISOR,
            middleX + ClientGuiLayout.MIDPOINT_DIVISOR,
            middleY,
            ClientGuiLayout.GUI_NAMING_BTN_WIDTH,
            ClientGuiLayout.ACTION_BTN_HEIGHT,
            text(ClientGuiIds.I18N_GUI_CANCEL));
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
            GL11.glColor4f(
                ClientGuiLayout.SUBSTRING_START_OFFSET,
                ClientGuiLayout.SUBSTRING_START_OFFSET,
                ClientGuiLayout.SUBSTRING_START_OFFSET,
                ClientGuiLayout.SUBSTRING_START_OFFSET);
            mc.getTextureManager()
                .bindTexture(TEXTURES);
            drawTexturedModalRect(
                width / ClientGuiLayout.MIDPOINT_DIVISOR + ClientGuiLayout.GUI_NAMING_VISIBILITY,
                height / ClientGuiLayout.MIDPOINT_DIVISOR - ClientGuiLayout.FILTER_BTN_Y,
                alwaysShow ? ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM_2 : ClientGuiLayout.GUI_NAMING_TEX_U,
                ClientGuiLayout.GUI_NAMING_TEX_V,
                ClientGuiLayout.ACTION_BTN_HEIGHT,
                ClientGuiLayout.ACTION_BTN_HEIGHT);
            if (mouseX >= visibility.xPosition && mouseX < visibility.xPosition + visibility.width
                && mouseY >= visibility.yPosition
                && mouseY < visibility.yPosition + visibility.height)
                drawCreativeTabHoveringText(
                    text(ClientGuiIds.I18N_GUI_TOUHOU_LITTLE_MAID_TAG_ALWAYS_SHOW),
                    mouseX,
                    mouseY);
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
        if (action.equals(ClientGuiIds.ID_NAMING_TEXT)) {
            if (pending || value == null || value.length() > MaidInteractionTuning.MAX_LENGTH) return false;
            int x = nameField.xPosition + nameField.width / ClientGuiLayout.MIDPOINT_DIVISOR,
                y = nameField.yPosition + nameField.height / ClientGuiLayout.MIDPOINT_DIVISOR;
            mouseClicked(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
            mouseMovedOrUp(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
            keyTyped('\0', Keyboard.KEY_HOME);
            int previousLength = nameField.getText()
                .length();
            for (int i = 0; i < previousLength; i++) keyTyped('\0', Keyboard.KEY_DELETE);
            try {
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    ClientDevelopmentAccess.hooks()
                        .injectCharacterInput(character);
                    keyTyped(character, Keyboard.KEY_NONE);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(ClientGuiDiagnostics.MSG_NATIVE_NAMING_GUI_TEXT_INPUT, failure);
            }
            return nameField.getText()
                .equals(value);
        }
        if (action.equals(ClientGuiIds.ID_NAMING_ESCAPE) || action.equals(ClientGuiIds.ID_NAMING_ENTER)) {
            keyTyped('\0', action.equals(ClientGuiIds.ID_NAMING_ESCAPE) ? Keyboard.KEY_ESCAPE : Keyboard.KEY_RETURN);
            return true;
        }
        var button = switch (action) {
            case ClientGuiIds.ID_NAMING_VISIBILITY -> visibility;
            case ClientGuiIds.ID_NAMING_SAVE, ClientGuiIds.ID_NAMING_REPEAT -> done;
            case ClientGuiIds.ID_NAMING_CANCEL -> cancel;
            default -> throw new IllegalArgumentException(ClientGuiDiagnostics.MSG_UNKNOWN_NAMING_ACTION);
        };
        if (!button.visible || !button.enabled) return false;
        int x = button.xPosition + button.width / ClientGuiLayout.MIDPOINT_DIVISOR,
            y = button.yPosition + button.height / ClientGuiLayout.MIDPOINT_DIVISOR;
        mouseClicked(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
        mouseMovedOrUp(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
        if (action.equals(ClientGuiIds.ID_NAMING_REPEAT)) {
            mouseClicked(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
            mouseMovedOrUp(x, y, ClientGuiLayout.GUI_MODELS_PAGE);
        }
        return true;
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty(ClientGuiIds.ID_TEXT, nameField.getText());
        result.addProperty(ClientGuiIds.ID_ALWAYSSHOW, alwaysShow);
        result.addProperty(ClientGuiIds.ID_PENDING, pending);
        result.addProperty(ClientGuiIds.ID_SUBMITTEDREQUESTS, submittedRequests);
        result.addProperty(ClientGuiIds.ID_WINDOWID, container.windowId);
        result.addProperty(ClientGuiIds.ID_CONTAINERCURRENT, mc.thePlayer.openContainer == container);
        result.addProperty(ClientGuiIds.ID_CANINTERACT, container.canInteractWith(mc.thePlayer));
        result.addProperty(ClientGuiIds.ID_DONEENABLED, done.enabled);
        result.addProperty(ClientGuiIds.ID_INPUTX, nameField.xPosition);
        result.addProperty(ClientGuiIds.ID_INPUTY, nameField.yPosition);
        result.addProperty(ClientGuiIds.ID_INPUTWIDTH, nameField.width);
        result.addProperty(ClientGuiIds.ID_INPUTHEIGHT, nameField.height);
        result.addProperty(ClientGuiIds.ID_VISIBILITYX, visibility.xPosition);
        result.addProperty(ClientGuiIds.ID_VISIBILITYY, visibility.yPosition);
        result.addProperty(ClientGuiIds.ID_VISIBILITYWIDTH, visibility.width);
        result.addProperty(ClientGuiIds.ID_VISIBILITYHEIGHT, visibility.height);
        result.addProperty(
            ClientGuiIds.ID_VISIBILITYTEXTUREU,
            alwaysShow ? ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM_2 : ClientGuiLayout.GUI_NAMING_TEX_U);
        return result;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
