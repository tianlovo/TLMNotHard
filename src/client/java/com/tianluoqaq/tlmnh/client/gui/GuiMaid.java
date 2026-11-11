package com.tianluoqaq.tlmnh.client.gui;

import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.StatCollector;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerLayout;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 状态取自服务端同步，按钮只发送容器动作，不在客户端修改女仆 */
@SideOnly(Side.CLIENT)
public final class GuiMaid extends GuiContainer {

    private final EntityMaid maid;

    private boolean homePage;

    public GuiMaid(ContainerMaid container) {
        super(container);
        maid = container.getMaid();
        xSize = MaidContainerLayout.WIDTH;
        ySize = MaidContainerLayout.HEIGHT;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        addButton(
            MaidContainerProtocol.ACTION_IDLE,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.IDLE_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_ATTACK,
            ClientGuiLayout.ATTACK_BTN_X,
            ClientGuiLayout.IDLE_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_RANGED,
            ClientGuiLayout.RANGED_BTN_X,
            ClientGuiLayout.IDLE_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_DAY,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.DAY_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_NIGHT,
            ClientGuiLayout.ATTACK_BTN_X,
            ClientGuiLayout.DAY_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_ALL_DAY,
            ClientGuiLayout.RANGED_BTN_X,
            ClientGuiLayout.DAY_BTN_Y,
            ClientGuiLayout.IDLE_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_PICKUP_ITEMS,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.PICKUP_ITEMS_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_PICKUP_EXPERIENCE,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.PICKUP_EXPERIENCE_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_SIT,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.SIT_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_MODELS,
            ClientGuiLayout.MODELS_BTN_X,
            ClientGuiLayout.IDLE_BTN_Y,
            ClientGuiLayout.MODELS_BTN_X);
        addButton(
            ClientGuiLayout.HOME_PANEL,
            ClientGuiLayout.MODELS_BTN_X,
            ClientGuiLayout.HOME_PANEL_BTN_Y,
            ClientGuiLayout.MODELS_BTN_X);
        addButton(
            MaidContainerProtocol.ACTION_HOME,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.IDLE_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_HOME_WORK,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.DAY_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_HOME_IDLE,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.HOME_IDLE_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_HOME_REST,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.HOME_REST_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        addButton(
            MaidContainerProtocol.ACTION_HOME_RESET,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.HOME_RESET_BTN_Y,
            ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH);
        refreshButtons();
    }

    private void addButton(int action, int x, int y, int width) {
        buttonList.add(new GuiButton(action, guiLeft + x, guiTop + y, width, ClientGuiLayout.ACTION_BTN_HEIGHT, ""));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!((ContainerMaid) inventorySlots).canInteractWith(mc.thePlayer)) {
            mc.thePlayer.closeScreen();
            return;
        }
        refreshButtons();
    }

    private void refreshButtons() {
        for (var button : buttonList) {
            button.visible = button.id == ClientGuiLayout.HOME_PANEL || button.id == MaidContainerProtocol.ACTION_MODELS
                || (button.id >= MaidContainerProtocol.ACTION_HOME ? homePage : !homePage);
            if (maid.isUnifiedCombat() && button.id == MaidContainerProtocol.ACTION_RANGED) button.visible = false;
            boolean selected = false;
            switch (button.id) {
                case MaidContainerProtocol.ACTION_IDLE, MaidContainerProtocol.ACTION_ATTACK, MaidContainerProtocol.ACTION_RANGED -> {
                    var task = MaidTask.values()[button.id];
                    button.displayString = text(ClientGuiIds.I18N_GUI_TLMNH_TASK + task.getId());
                    selected = maid.getTask() == task;
                }
                case MaidContainerProtocol.ACTION_DAY, MaidContainerProtocol.ACTION_NIGHT, MaidContainerProtocol.ACTION_ALL_DAY -> {
                    int ordinal = button.id - MaidContainerProtocol.ACTION_DAY;
                    button.displayString = text(ClientGuiIds.I18N_GUI_TLMNH_SCHEDULE + ordinal);
                    selected = maid.getSchedule()
                        .ordinal() == ordinal;
                }
                case MaidContainerProtocol.ACTION_PICKUP_ITEMS -> button.displayString = toggle(
                    ClientGuiIds.I18N_GUI_TLMNH_PICKUP_ITEMS,
                    maid.canPickupItems());
                case MaidContainerProtocol.ACTION_PICKUP_EXPERIENCE -> button.displayString = toggle(
                    ClientGuiIds.I18N_GUI_TLMNH_PICKUP_EXPERIENCE,
                    maid.canPickupExperience());
                case MaidContainerProtocol.ACTION_SIT -> button.displayString = text(
                    maid.isSitting() ? ClientGuiIds.I18N_GUI_TLMNH_STAND : ClientGuiIds.I18N_GUI_TLMNH_SIT);
                case MaidContainerProtocol.ACTION_MODELS -> button.displayString = text(
                    ClientGuiIds.I18N_GUI_TLMNH_MODELS_OPEN);
                case MaidContainerProtocol.ACTION_HOME -> button.displayString = toggle(
                    ClientGuiIds.I18N_GUI_TLMNH_HOME,
                    maid.getHomeArea()
                        .enabled());
                case MaidContainerProtocol.ACTION_HOME_WORK -> button.displayString = text(
                    ClientGuiIds.I18N_GUI_TLMNH_HOME_WORK);
                case MaidContainerProtocol.ACTION_HOME_IDLE -> button.displayString = text(
                    ClientGuiIds.I18N_GUI_TLMNH_HOME_IDLE);
                case MaidContainerProtocol.ACTION_HOME_REST -> button.displayString = text(
                    ClientGuiIds.I18N_GUI_TLMNH_HOME_REST);
                case MaidContainerProtocol.ACTION_HOME_RESET -> button.displayString = text(
                    ClientGuiIds.I18N_GUI_TLMNH_HOME_RESET);
                case ClientGuiLayout.HOME_PANEL -> button.displayString = text(
                    homePage ? ClientGuiIds.I18N_GUI_TLMNH_HOME_BACK : ClientGuiIds.I18N_GUI_TLMNH_HOME_OPEN);
                default -> {}
            }
            button.enabled = !selected && (button.id != MaidContainerProtocol.ACTION_SIT || maid.ridingEntity == null)
                && (button.id != MaidContainerProtocol.ACTION_MODELS || mc.thePlayer.inventory.getItemStack() == null);
            if (button.id >= MaidContainerProtocol.ACTION_HOME
                && button.id <= MaidContainerProtocol.ACTION_HOME_RESET) {
                button.enabled = maid.ridingEntity == null && maid.riddenByEntity == null;
                if (button.id > MaidContainerProtocol.ACTION_HOME) button.enabled &= maid.getHomeArea()
                    .enabled();
                if (button.id >= MaidContainerProtocol.ACTION_HOME_WORK
                    && button.id <= MaidContainerProtocol.ACTION_HOME_REST)
                    button.enabled &= maid.getHomeArea()
                        .sameDimension();
            }
        }
    }

    private static String toggle(String key, boolean value) {
        return text(key) + ClientGuiDiagnostics.COLON_SPACE_DELIMITER
            + text(value ? ClientGuiIds.I18N_GUI_TLMNH_ENABLED : ClientGuiIds.I18N_GUI_TLMNH_DISABLED);
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        if (button.id == ClientGuiLayout.HOME_PANEL) {
            homePage = !homePage;
            refreshButtons();
        } else if (button.id == MaidContainerProtocol.ACTION_MODELS) openModelSelector();
        else mc.playerController.sendEnchantPacket(inventorySlots.windowId, button.id);
    }

    public boolean openModelSelector() {
        if (mc.currentScreen != this || mc.thePlayer.inventory.getItemStack() != null
            || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        mc.playerController.sendEnchantPacket(inventorySlots.windowId, MaidContainerProtocol.ACTION_MODELS);
        return true;
    }

    public boolean openHomeSettings() {
        if (mc.currentScreen != this || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        homePage = true;
        refreshButtons();
        return true;
    }

    public boolean isHomeSettingsPage() {
        return homePage;
    }

    /** 与鼠标入口共用实际按钮及容器消息，返回请求已发送，不推定服务端接受 */
    public boolean requestHomeAction(int action) {
        if (!homePage || mc.currentScreen != this || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        for (var button : buttonList) if (button.id == action && button.visible
            && button.enabled
            && action >= MaidContainerProtocol.ACTION_HOME
            && action <= MaidContainerProtocol.ACTION_HOME_RESET) {
                actionPerformed(button);
                return true;
            }
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        for (var button : buttonList) if (button.visible && button.id == MaidContainerProtocol.ACTION_HOME
            && mouseX >= button.xPosition
            && mouseY >= button.yPosition
            && mouseX < button.xPosition + button.width
            && mouseY < button.yPosition + button.height) {
                var home = maid.getHomeArea()
                    .settings();
                var lines = new ArrayList<String>();
                lines.add(text(ClientGuiIds.I18N_GUI_TLMNH_HOME_TIP));
                lines.add(
                    StatCollector
                        .translateToLocalFormatted(ClientGuiIds.I18N_GUI_TLMNH_HOME_DIMENSION, home.dimension));
                lines.add(
                    text(ClientGuiIds.I18N_GUI_TLMNH_HOME_WORK) + ClientGuiDiagnostics.COLON_SPACE_DELIMITER
                        + home.work);
                lines.add(
                    text(ClientGuiIds.I18N_GUI_TLMNH_HOME_IDLE) + ClientGuiDiagnostics.COLON_SPACE_DELIMITER
                        + home.idle);
                lines.add(
                    text(ClientGuiIds.I18N_GUI_TLMNH_HOME_REST) + ClientGuiDiagnostics.COLON_SPACE_DELIMITER
                        + home.rest);
                drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
            }
        if (mc.thePlayer.inventory.getItemStack() == null) return;
        for (var button : buttonList) {
            if (button.id == MaidContainerProtocol.ACTION_MODELS && mouseX >= button.xPosition
                && mouseY >= button.yPosition
                && mouseX < button.xPosition + button.width
                && mouseY < button.yPosition + button.height)
                drawHoveringText(
                    Collections.singletonList(text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_PUT_DOWN_ITEM)),
                    mouseX,
                    mouseY,
                    fontRendererObj);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(
            maid.getCommandSenderName(),
            ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.GUI_TEXT_Y,
            ClientGuiLayout.RGB_COLOR_F4F1E9);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_HANDS),
            ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.GUI_TEXT_Y_2,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_ARMOR),
            ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.HOME_PANEL_BTN_Y,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_BACKPACK),
            ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.GUI_TEXT_Y_3,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_CONTAINER_INVENTORY),
            ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.GUI_TEXT_Y_4,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        if (homePage) {
            fontRendererObj.drawString(
                text(ClientGuiIds.I18N_GUI_TLMNH_HOME_TITLE),
                ClientGuiLayout.IDLE_BTN_X,
                ClientGuiLayout.GUI_TEXT_Y_2,
                ClientGuiLayout.RGB_COLOR_D2D8DD);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(
                    StatCollector.translateToLocalFormatted(
                        ClientGuiIds.I18N_GUI_TLMNH_HOME_DIMENSION,
                        maid.getHomeArea()
                            .settings().dimension),
                    ClientGuiLayout.PICKUP_ITEMS_BTN_WIDTH),
                ClientGuiLayout.IDLE_BTN_X,
                ClientGuiLayout.IDLE_BTN_X,
                ClientGuiLayout.RGB_COLOR_D2D8DD);
            return;
        }
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_TASK_2),
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.GUI_TEXT_Y_2,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_SCHEDULE_2),
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.HOME_PANEL_BTN_Y,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            text(ClientGuiIds.I18N_GUI_TLMNH_PICKUP),
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.GUI_TEXT_Y_3,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted(ClientGuiIds.I18N_GUI_TLMNH_HEALTH, maid.getHealth(), maid.getMaxHealth()),
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(ClientGuiIds.I18N_GUI_TLMNH_EXPERIENCE, maid.getExperience()),
            ClientGuiLayout.IDLE_BTN_X,
            ClientGuiLayout.GUI_DRAW_GUI_CONTAINER_FOREGROUND_LAYER_PARAM,
            ClientGuiLayout.RGB_COLOR_D2D8DD);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, ClientGuiLayout.ARGB_COLOR_FF25313C);
        drawRect(
            guiLeft + ClientGuiLayout.GUI_RECT_LEFT,
            guiTop + ClientGuiLayout.GUI_RECT_TOP,
            guiLeft + ClientGuiLayout.GUI_RECT_RIGHT,
            guiTop + ySize - ClientGuiLayout.GUI_TEXT_X,
            ClientGuiLayout.ARGB_COLOR_FF4E6170);
        for (var slot : inventorySlots.inventorySlots) {
            int x = guiLeft + slot.xDisplayPosition;
            int y = guiTop + slot.yDisplayPosition;
            drawRect(
                x - 1,
                y - 1,
                x + ClientGuiLayout.GUI_RECT_RIGHT_2,
                y + ClientGuiLayout.GUI_RECT_RIGHT_2,
                ClientGuiLayout.ARGB_COLOR_FF101922);
            drawRect(
                x,
                y,
                x + ClientGuiLayout.GUI_RECT_TOP,
                y + ClientGuiLayout.GUI_RECT_TOP,
                ClientGuiLayout.ARGB_COLOR_FF84919A);
        }
    }
}
