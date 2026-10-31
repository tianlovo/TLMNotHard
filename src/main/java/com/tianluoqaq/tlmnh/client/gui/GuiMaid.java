package com.tianluoqaq.tlmnh.client.gui;

import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.StatCollector;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 状态取自服务端同步，按钮只发送容器动作，不在客户端修改女仆 */
@SideOnly(Side.CLIENT)
public final class GuiMaid extends GuiContainer {

    private final EntityMaid maid;
    private static final int HOME_PANEL = 1000;
    private boolean homePage;

    public GuiMaid(ContainerMaid container) {
        super(container);
        maid = container.getMaid();
        xSize = ContainerMaid.WIDTH;
        ySize = ContainerMaid.HEIGHT;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        addButton(ContainerMaid.ACTION_IDLE, 184, 28, 40);
        addButton(ContainerMaid.ACTION_ATTACK, 226, 28, 40);
        addButton(ContainerMaid.ACTION_RANGED, 268, 28, 40);
        addButton(ContainerMaid.ACTION_DAY, 184, 64, 40);
        addButton(ContainerMaid.ACTION_NIGHT, 226, 64, 40);
        addButton(ContainerMaid.ACTION_ALL_DAY, 268, 64, 40);
        addButton(ContainerMaid.ACTION_PICKUP_ITEMS, 184, 100, 124);
        addButton(ContainerMaid.ACTION_PICKUP_EXPERIENCE, 184, 122, 124);
        addButton(ContainerMaid.ACTION_SIT, 184, 154, 124);
        addButton(ContainerMaid.ACTION_MODELS, 84, 28, 84);
        addButton(HOME_PANEL, 84, 54, 84);
        addButton(ContainerMaid.ACTION_HOME, 184, 28, 124);
        addButton(ContainerMaid.ACTION_HOME_WORK, 184, 64, 124);
        addButton(ContainerMaid.ACTION_HOME_IDLE, 184, 86, 124);
        addButton(ContainerMaid.ACTION_HOME_REST, 184, 108, 124);
        addButton(ContainerMaid.ACTION_HOME_RESET, 184, 142, 124);
        refreshButtons();
    }

    private void addButton(int action, int x, int y, int width) {
        buttonList.add(new GuiButton(action, guiLeft + x, guiTop + y, width, 20, ""));
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
            button.visible = button.id == HOME_PANEL || button.id == ContainerMaid.ACTION_MODELS
                || (button.id >= ContainerMaid.ACTION_HOME ? homePage : !homePage);
            boolean selected = false;
            switch (button.id) {
                case ContainerMaid.ACTION_IDLE, ContainerMaid.ACTION_ATTACK, ContainerMaid.ACTION_RANGED -> {
                    var task = MaidTask.values()[button.id];
                    button.displayString = text("gui.tlmnh.task." + task.getId());
                    selected = maid.getTask() == task;
                }
                case ContainerMaid.ACTION_DAY, ContainerMaid.ACTION_NIGHT, ContainerMaid.ACTION_ALL_DAY -> {
                    int ordinal = button.id - ContainerMaid.ACTION_DAY;
                    button.displayString = text("gui.tlmnh.schedule." + ordinal);
                    selected = maid.getSchedule()
                        .ordinal() == ordinal;
                }
                case ContainerMaid.ACTION_PICKUP_ITEMS -> button.displayString = toggle(
                    "gui.tlmnh.pickup_items",
                    maid.canPickupItems());
                case ContainerMaid.ACTION_PICKUP_EXPERIENCE -> button.displayString = toggle(
                    "gui.tlmnh.pickup_experience",
                    maid.canPickupExperience());
                case ContainerMaid.ACTION_SIT -> button.displayString = text(
                    maid.isSitting() ? "gui.tlmnh.stand" : "gui.tlmnh.sit");
                case ContainerMaid.ACTION_MODELS -> button.displayString = text("gui.tlmnh.models.open");
                case ContainerMaid.ACTION_HOME -> button.displayString = toggle(
                    "gui.tlmnh.home",
                    maid.getHomeArea()
                        .enabled());
                case ContainerMaid.ACTION_HOME_WORK -> button.displayString = text("gui.tlmnh.home.work");
                case ContainerMaid.ACTION_HOME_IDLE -> button.displayString = text("gui.tlmnh.home.idle");
                case ContainerMaid.ACTION_HOME_REST -> button.displayString = text("gui.tlmnh.home.rest");
                case ContainerMaid.ACTION_HOME_RESET -> button.displayString = text("gui.tlmnh.home.reset");
                case HOME_PANEL -> button.displayString = text(
                    homePage ? "gui.tlmnh.home.back" : "gui.tlmnh.home.open");
                default -> {}
            }
            button.enabled = !selected && (button.id != ContainerMaid.ACTION_SIT || maid.ridingEntity == null)
                && (button.id != ContainerMaid.ACTION_MODELS || mc.thePlayer.inventory.getItemStack() == null);
            if (button.id >= ContainerMaid.ACTION_HOME && button.id <= ContainerMaid.ACTION_HOME_RESET) {
                button.enabled = maid.ridingEntity == null && maid.riddenByEntity == null;
                if (button.id > ContainerMaid.ACTION_HOME) button.enabled &= maid.getHomeArea()
                    .enabled();
                if (button.id >= ContainerMaid.ACTION_HOME_WORK && button.id <= ContainerMaid.ACTION_HOME_REST)
                    button.enabled &= maid.getHomeArea()
                        .sameDimension();
            }
        }
    }

    private static String toggle(String key, boolean value) {
        return text(key) + ": " + text(value ? "gui.tlmnh.enabled" : "gui.tlmnh.disabled");
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        if (button.id == HOME_PANEL) {
            homePage = !homePage;
            refreshButtons();
        } else if (button.id == ContainerMaid.ACTION_MODELS) openModelSelector();
        else mc.playerController.sendEnchantPacket(inventorySlots.windowId, button.id);
    }

    public boolean openModelSelector() {
        if (mc.currentScreen != this || mc.thePlayer.inventory.getItemStack() != null
            || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        mc.playerController.sendEnchantPacket(inventorySlots.windowId, ContainerMaid.ACTION_MODELS);
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
            && action >= ContainerMaid.ACTION_HOME
            && action <= ContainerMaid.ACTION_HOME_RESET) {
                actionPerformed(button);
                return true;
            }
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        for (var button : buttonList) if (button.visible && button.id == ContainerMaid.ACTION_HOME
            && mouseX >= button.xPosition
            && mouseY >= button.yPosition
            && mouseX < button.xPosition + button.width
            && mouseY < button.yPosition + button.height) {
                var home = maid.getHomeArea()
                    .settings();
                var lines = new ArrayList<String>();
                lines.add(text("gui.tlmnh.home.tip"));
                lines.add(StatCollector.translateToLocalFormatted("gui.tlmnh.home.dimension", home.dimension));
                lines.add(text("gui.tlmnh.home.work") + ": " + home.work);
                lines.add(text("gui.tlmnh.home.idle") + ": " + home.idle);
                lines.add(text("gui.tlmnh.home.rest") + ": " + home.rest);
                drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
            }
        if (mc.thePlayer.inventory.getItemStack() == null) return;
        for (var button : buttonList) {
            if (button.id == ContainerMaid.ACTION_MODELS && mouseX >= button.xPosition
                && mouseY >= button.yPosition
                && mouseX < button.xPosition + button.width
                && mouseY < button.yPosition + button.height)
                drawHoveringText(
                    Collections.singletonList(text("gui.tlmnh.models.put_down_item")),
                    mouseX,
                    mouseY,
                    fontRendererObj);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(maid.getCommandSenderName(), 8, 6, 0xF4F1E9);
        fontRendererObj.drawString(text("gui.tlmnh.hands"), 8, 18, 0xD2D8DD);
        fontRendererObj.drawString(text("gui.tlmnh.armor"), 8, 54, 0xD2D8DD);
        fontRendererObj.drawString(text("gui.tlmnh.backpack"), 8, 90, 0xD2D8DD);
        fontRendererObj.drawString(text("container.inventory"), 8, 128, 0xD2D8DD);
        if (homePage) {
            fontRendererObj.drawString(text("gui.tlmnh.home.title"), 184, 18, 0xD2D8DD);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(
                    StatCollector.translateToLocalFormatted(
                        "gui.tlmnh.home.dimension",
                        maid.getHomeArea()
                            .settings().dimension),
                    124),
                184,
                184,
                0xD2D8DD);
            return;
        }
        fontRendererObj.drawString(text("gui.tlmnh.task"), 184, 18, 0xD2D8DD);
        fontRendererObj.drawString(text("gui.tlmnh.schedule"), 184, 54, 0xD2D8DD);
        fontRendererObj.drawString(text("gui.tlmnh.pickup"), 184, 90, 0xD2D8DD);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gui.tlmnh.health", maid.getHealth(), maid.getMaxHealth()),
            184,
            184,
            0xD2D8DD);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gui.tlmnh.experience", maid.getExperience()),
            184,
            198,
            0xD2D8DD);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xFF25313C);
        drawRect(guiLeft + 176, guiTop + 16, guiLeft + 177, guiTop + ySize - 8, 0xFF4E6170);
        for (var slot : inventorySlots.inventorySlots) {
            int x = guiLeft + slot.xDisplayPosition;
            int y = guiTop + slot.yDisplayPosition;
            drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF101922);
            drawRect(x, y, x + 16, y + 16, 0xFF84919A);
        }
    }
}
