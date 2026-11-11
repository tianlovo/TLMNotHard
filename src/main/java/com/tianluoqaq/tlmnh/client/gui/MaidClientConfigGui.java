package com.tianluoqaq.tlmnh.client.gui;

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

    private static final int FREQUENCY = 0, BUBBLES = 1, CACHE = 2, SAVE = 3, CANCEL = 4;
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
        int x = width / 2 - 130, y = height / 2 - 46;
        slider = new GuiSlider(
            FREQUENCY,
            x,
            y,
            260,
            20,
            text("gui.touhou_little_maid.maid_config.sound_frequency") + ": ",
            "%",
            0,
            100,
            frequency,
            false,
            true,
            changed -> frequency = changed.getValueInt());
        buttonList.add(slider);
        buttonList.add(new GuiButton(BUBBLES, x, y + 26, 260, 20, ""));
        buttonList.add(new GuiButton(CACHE, x, y + 52, 260, 20, ""));
        buttonList.add(new GuiButton(SAVE, x, y + 90, 126, 20, text("gui.done")));
        buttonList.add(new GuiButton(CANCEL, x + 134, y + 90, 126, 20, text("gui.cancel")));
        labels();
    }

    private void labels() {
        for (var button : buttonList) {
            if (button.id == BUBBLES)
                button.displayString = text("gui.touhou_little_maid.maid_config.show_chat_bubble") + ": "
                    + text(bubbles ? "options.on" : "options.off");
            if (button.id == CACHE) button.displayString = text("gui.touhou_little_maid.skin.enable_cache") + ": "
                + text(cache ? "options.on" : "options.off");
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || mc.currentScreen != this || !valid()) return;
        switch (button.id) {
            case BUBBLES -> bubbles = !bubbles;
            case CACHE -> cache = !cache;
            case SAVE -> {
                MaidClientPreferences.apply(frequency, bubbles, cache);
                returnToParent();
                return;
            }
            case CANCEL -> {
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
            text("gui.touhou_little_maid.button.global_config"),
            width / 2,
            height / 2 - 88,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            text("gui.tlmnh.client_config.scope"),
            width / 2,
            height / 2 - 71,
            0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTick);
    }

    /** 开发入口仍经过实际slider或button分发，不直接改配置文件 */
    public boolean requestAction(String action) {
        if (mc.currentScreen != this || !valid()) return false;
        int id = switch (action) {
            case "global_sound_zero", "global_sound_full", "global_sound_mid" -> FREQUENCY;
            case "global_bubbles" -> BUBBLES;
            case "global_cache" -> CACHE;
            case "global_save" -> SAVE;
            case "global_cancel" -> CANCEL;
            default -> throw new IllegalArgumentException("Unknown client configuration action");
        };
        if (id == FREQUENCY) {
            int percent = action.equals("global_sound_zero") ? 0 : action.equals("global_sound_full") ? 100 : 37;
            int x = slider.xPosition + 4 + Math.round((slider.width - 8) * percent / 100F);
            int y = slider.yPosition + slider.height / 2;
            mouseClicked(x, y, 0);
            mouseMovedOrUp(x, y, 0);
        } else for (var button : buttonList) if (button.id == id && button.enabled) {
            mouseClicked(button.xPosition + button.width / 2, button.yPosition + button.height / 2, 0);
            mouseMovedOrUp(button.xPosition + button.width / 2, button.yPosition + button.height / 2, 0);
            return true;
        }
        return id == FREQUENCY;
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("draftSoundFrequency", frequency);
        result.addProperty("draftChatBubbles", bubbles);
        result.addProperty("draftIconCache", cache);
        result.addProperty("savedSoundFrequency", MaidClientPreferences.globalMaidSoundFrequency());
        result.addProperty("savedChatBubbles", MaidClientPreferences.showChatBubbles());
        result.addProperty("savedIconCache", MaidClientPreferences.modelIconCache());
        result.addProperty("windowId", container.windowId);
        result.addProperty("containerCurrent", valid());
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
