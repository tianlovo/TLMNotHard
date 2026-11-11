package com.tianluoqaq.tlmnh.client.gui.config;

import java.util.Objects;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.google.gson.JsonObject;

import cpw.mods.fml.client.config.GuiSlider;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原全局设置按钮的本地配置入口，只编辑已实现的声音/气泡/图标偏好，保留原女仆窗口 */
@SideOnly(Side.CLIENT)
public final class MaidClientConfigGui extends GuiScreen {


    private final GuiScreen parent;
    private final Container container;
    private int frequency = MaidClientPreferences.globalMaidSoundFrequency();
    private boolean bubbles = MaidClientPreferences.showChatBubbles(), cache = MaidClientPreferences.modelIconCache();
    private GuiSlider slider;

    public MaidClientConfigGui(GuiScreen parent, Container container) {
        this.parent = Objects.requireNonNull(parent);
        this.container = Objects.requireNonNull(container);
    }

    public Container getContainer() {
        return container;
    }

    private boolean valid() {
        return mc.thePlayer != null && mc.thePlayer.openContainer == container
            && container.canInteractWith(mc.thePlayer)
            && mc.thePlayer.inventory.getItemStack() == null;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int x = width / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR - ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_X_OFFSET, y = height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR - ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_Y_OFFSET;
        slider = new GuiSlider(
            ClientConfigGuiConfigSpec.FREQUENCY,
            x,
            y,
            ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM,
            ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM_2,
            text(ClientConfigGuiConfigSpec.I18N_GUI_TOUHOU_LITTLE_MAID_MAID_CONFIG_SOUND_FREQUE) + ClientConfigGuiConfigSpec.COLON_SPACE_DELIMITER,
            ClientConfigGuiConfigSpec.PERCENT_SUFFIX,
            ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE,
            ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM,
            frequency,
            false,
            true,
            changed -> frequency = changed.getValueInt());
        buttonList.add(slider);
        buttonList.add(new GuiButton(ClientConfigGuiConfigSpec.BUBBLES, x, y + ClientConfigGuiConfigSpec.BUBBLES_BTN_Y, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM_2, ""));
        buttonList.add(new GuiButton(ClientConfigGuiConfigSpec.CACHE, x, y + ClientConfigGuiConfigSpec.CACHE_BTN_Y, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM_2, ""));
        buttonList.add(new GuiButton(ClientConfigGuiConfigSpec.SAVE, x, y + ClientConfigGuiConfigSpec.SAVE_BTN_Y, ClientConfigGuiConfigSpec.SAVE_BTN_WIDTH, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM_2, text(ClientConfigGuiConfigSpec.I18N_GUI_DONE)));
        buttonList.add(new GuiButton(ClientConfigGuiConfigSpec.CANCEL, x + ClientConfigGuiConfigSpec.CANCEL_BTN_X, y + ClientConfigGuiConfigSpec.SAVE_BTN_Y, ClientConfigGuiConfigSpec.SAVE_BTN_WIDTH, ClientConfigGuiConfigSpec.CLIENT_CONFIG_INIT_GUI_PARAM_2, text(ClientConfigGuiConfigSpec.I18N_GUI_CANCEL)));
        labels();
    }

    private void labels() {
        for (var button : buttonList) {
            if (button.id == ClientConfigGuiConfigSpec.BUBBLES)
                button.displayString = text(ClientConfigGuiConfigSpec.I18N_GUI_TOUHOU_LITTLE_MAID_MAID_CONFIG_SHOW_CHAT_BU) + ClientConfigGuiConfigSpec.COLON_SPACE_DELIMITER
                    + text(bubbles ? ClientConfigGuiConfigSpec.ID_OPTIONS_ON : ClientConfigGuiConfigSpec.ID_OPTIONS_OFF);
            if (button.id == ClientConfigGuiConfigSpec.CACHE) button.displayString = text(ClientConfigGuiConfigSpec.I18N_GUI_TOUHOU_LITTLE_MAID_SKIN_ENABLE_CACHE) + ClientConfigGuiConfigSpec.COLON_SPACE_DELIMITER
                + text(cache ? ClientConfigGuiConfigSpec.ID_OPTIONS_ON : ClientConfigGuiConfigSpec.ID_OPTIONS_OFF);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || mc.currentScreen != this || !valid()) return;
        switch (button.id) {
            case ClientConfigGuiConfigSpec.BUBBLES -> bubbles = !bubbles;
            case ClientConfigGuiConfigSpec.CACHE -> cache = !cache;
            case ClientConfigGuiConfigSpec.SAVE -> {
                MaidClientPreferences.apply(frequency, bubbles, cache);
                returnToParent();
                return;
            }
            case ClientConfigGuiConfigSpec.CANCEL -> {
                returnToParent();
                return;
            }
            default -> {}
        }
        labels();
    }

    private void returnToParent() {
        if (valid()) mc.displayGuiScreen(parent);
        else if (mc.thePlayer != null) mc.thePlayer.closeScreen();
    }

    @Override
    public void updateScreen() {
        if (!valid() && mc.thePlayer != null) mc.thePlayer.closeScreen();
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) returnToParent();
        else super.keyTyped(character, key);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            text(ClientConfigGuiConfigSpec.I18N_GUI_TOUHOU_LITTLE_MAID_BUTTON_GLOBAL_CONFIG),
            width / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR,
            height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR - ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM_2,
            ClientConfigGuiConfigSpec.RGB_WHITE);
        drawCenteredString(
            fontRendererObj,
            text(ClientConfigGuiConfigSpec.I18N_GUI_TLMNH_CLIENT_CONFIG_SCOPE),
            width / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR,
            height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR - ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM_3,
            ClientConfigGuiConfigSpec.RGB_SILVER);
        super.drawScreen(mouseX, mouseY, partialTick);
    }

    /** 开发入口仍经过实际slider或button分发，不直接改配置文件 */
    public boolean requestAction(String action) {
        if (mc.currentScreen != this || !valid()) return false;
        int id = switch (action) {
            case ClientConfigGuiConfigSpec.ID_GLOBAL_SOUND_ZERO, ClientConfigGuiConfigSpec.ID_GLOBAL_SOUND_FULL, ClientConfigGuiConfigSpec.ID_GLOBAL_SOUND_MID -> ClientConfigGuiConfigSpec.FREQUENCY;
            case ClientConfigGuiConfigSpec.ID_GLOBAL_BUBBLES -> ClientConfigGuiConfigSpec.BUBBLES;
            case ClientConfigGuiConfigSpec.ID_GLOBAL_CACHE -> ClientConfigGuiConfigSpec.CACHE;
            case ClientConfigGuiConfigSpec.ID_GLOBAL_SAVE -> ClientConfigGuiConfigSpec.SAVE;
            case ClientConfigGuiConfigSpec.ID_GLOBAL_CANCEL -> ClientConfigGuiConfigSpec.CANCEL;
            default -> throw new IllegalArgumentException(ClientConfigGuiConfigSpec.MSG_UNKNOWN_CLIENT_CONFIGURATION_ACTION);
        };
        if (id == ClientConfigGuiConfigSpec.FREQUENCY) {
            int percent = action.equals(ClientConfigGuiConfigSpec.ID_GLOBAL_SOUND_ZERO) ? ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE : action.equals(ClientConfigGuiConfigSpec.ID_GLOBAL_SOUND_FULL) ? ClientConfigGuiConfigSpec.CLIENT_CONFIG_DOMAIN_PARAM : ClientConfigGuiConfigSpec.CLIENT_CONFIG_REQUEST_ACTION_PARAM;
            int x = slider.xPosition + ClientConfigGuiConfigSpec.CLIENT_CONFIG_REQUEST_ACTION_X_OFFSET + Math.round((slider.width - ClientConfigGuiConfigSpec.CLIENT_CONFIG_REQUEST_ACTION_X_OFFSET_2) * percent / ClientConfigGuiConfigSpec.FULL_PERCENTAGE_F);
            int y = slider.yPosition + slider.height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR;
            mouseClicked(x, y, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE);
            mouseMovedOrUp(x, y, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE);
        } else for (var button : buttonList) if (button.id == id && button.enabled) {
            mouseClicked(button.xPosition + button.width / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR, button.yPosition + button.height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE);
            mouseMovedOrUp(button.xPosition + button.width / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR, button.yPosition + button.height / ClientConfigGuiConfigSpec.MIDPOINT_DIVISOR, ClientConfigGuiConfigSpec.CLIENT_CONFIG_DEFAULT_ZERO_BASE);
            return true;
        }
        return id == ClientConfigGuiConfigSpec.FREQUENCY;
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty(ClientConfigGuiConfigSpec.ID_DRAFTSOUNDFREQUENCY, frequency);
        result.addProperty(ClientConfigGuiConfigSpec.ID_DRAFTCHATBUBBLES, bubbles);
        result.addProperty(ClientConfigGuiConfigSpec.ID_DRAFTICONCACHE, cache);
        result.addProperty(ClientConfigGuiConfigSpec.ID_SAVEDSOUNDFREQUENCY, MaidClientPreferences.globalMaidSoundFrequency());
        result.addProperty(ClientConfigGuiConfigSpec.ID_SAVEDCHATBUBBLES, MaidClientPreferences.showChatBubbles());
        result.addProperty(ClientConfigGuiConfigSpec.ID_SAVEDICONCACHE, MaidClientPreferences.modelIconCache());
        result.addProperty(ClientConfigGuiConfigSpec.ID_WINDOWID, container.windowId);
        result.addProperty(ClientConfigGuiConfigSpec.ID_CONTAINERCURRENT, valid());
        return result;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
