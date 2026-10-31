/*
 * Ported from TouhouLittleMaid, commit 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original path: client/gui/entity/maid/AbstractMaidContainerGui.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT license
 * Adaptation: Forge 1.7.10 GUI API, existing authoritative actions, built-in scope
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.tianluoqaq.tlmnh.client.gui.MaidAtlasButton;
import com.tianluoqaq.tlmnh.client.render.MaidGuiPreview;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原版主页面的首段移植，未接入的子页保留原图标并注明开发状态 */
@SideOnly(Side.CLIENT)
public abstract class AbstractMaidContainerGui<T extends AbstractMaidContainer> extends GuiContainer {

    private static final ResourceLocation BG = texture("maid_gui_main");
    private static final ResourceLocation SIDE = texture("maid_gui_side");
    private static final ResourceLocation BUTTON = texture("maid_gui_button");
    private static final ResourceLocation TASK = texture("maid_gui_task");
    private static final String[] TASK_KEYS = { "idle", "attack", "ranged_attack" };
    private static final Item[] TASK_ICONS = { Items.feather, Items.diamond_sword, Items.bow };
    private static final int TASK_SWITCH = 100, TASK_CLOSE = 101, SCHEDULE = 102;
    private static final int SKIN = 103, INFO = 104, SOUND = 105, RIDE = 106, DOWNLOAD = 107;
    private static final int MAIN_TAB = 108, TASK_TAB = 109, CONFIG_TAB = 110, BOOK = 111, GLOBAL = 112, BAUBLE = 115;
    protected final EntityMaid maid;
    private boolean taskListOpen;
    private int lastMouseX, lastMouseY;

    protected AbstractMaidContainerGui(T container) {
        super(container);
        maid = container.getMaid();
        xSize = 256;
        ySize = 256;
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("touhou_little_maid", "textures/gui/" + name + ".png");
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        add(SKIN, 62, 14, 9, 9, BUTTON, 72, 43, 10, 0, "skin");
        add(INFO, 8, 14, 9, 9, BUTTON, 72, 65, 10, 0, "info");
        add(SOUND, 52, 14, 9, 9, BUTTON, 144, 43, 10, 0, "sound");
        add(TASK_SWITCH, 4, 159, 71, 21, BUTTON, 0, 42, 22, 0, "task");
        add(SCHEDULE, 9, 187, 61, 13, BUTTON, 82, 43, 0, 0, "schedule");
        add(ContainerMaid.ACTION_HOME, 9, 206, 20, 20, BUTTON, 0, 0, 21, 21, "home");
        add(ContainerMaid.ACTION_PICKUP_ALL, 30, 206, 20, 20, BUTTON, 42, 0, 21, 21, "pickup");
        add(RIDE, 51, 206, 20, 20, BUTTON, 84, 0, 21, 21, "maid_riding_set");
        add(DOWNLOAD, 20, 230, 41, 20, BUTTON, 0, 86, 20, 0, "model_download");
        add(MAIN_TAB, 94, 5, 24, 26, SIDE, 107, 21, 0, 0, "main");
        add(TASK_TAB, 119, 5, 24, 26, SIDE, 132, 21, 0, 0, "task_config");
        add(CONFIG_TAB, 144, 5, 24, 26, SIDE, 157, 21, 0, 0, "maid_config");
        add(BOOK, 251, 37, 26, 24, SIDE, 209, 107, 0, 0, "task_book");
        add(GLOBAL, 251, 62, 26, 24, SIDE, 209, 132, 0, 0, "global_config");
        add(BAUBLE, 85, 97, 54, 63, texture("bauble_button"), 0, 0, 0, 0, "");
        add(TASK_CLOSE, -19, 9, 13, 13, TASK, 127, 0, 14, 0, "close");
        add(113, -89, 9, 16, 13, TASK, 110, 0, 14, 0, "previous");
        add(114, -72, 9, 16, 13, TASK, 93, 0, 14, 0, "next");
        for (int task = 0; task < 3; task++) add(task, -89, 23 + 19 * task, 83, 19, TASK, 93, 28, 20, 0, "");
        refreshButtons();
        initAdditionWidgets();
    }

    protected void initAdditionWidgets() {}

    protected boolean isConfigPage() {
        return false;
    }

    private void add(int id, int x, int y, int width, int height, ResourceLocation atlas, int u, int v, int hover,
        int selected, String key) {
        buttonList
            .add(new MaidAtlasButton(id, guiLeft + x, guiTop + y, width, height, atlas, u, v, hover, selected, key));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!inventorySlots.canInteractWith(mc.thePlayer)) mc.thePlayer.closeScreen();
        else refreshButtons();
    }

    private void refreshButtons() {
        for (var raw : buttonList) {
            if (!(raw instanceof MaidAtlasButton button)) continue;
            button.visible = button.id <= ContainerMaid.ACTION_RANGED || button.id == TASK_CLOSE
                || button.id == 113
                || button.id == 114 ? taskListOpen : true;
            if (button.id == BAUBLE && isConfigPage()) button.visible = false;
            button.enabled = switch (button.id) {
                case RIDE, DOWNLOAD, TASK_TAB, BOOK, GLOBAL, BAUBLE, 113, 114 -> false;
                case MAIN_TAB -> isConfigPage() && mc.thePlayer.inventory.getItemStack() == null;
                case CONFIG_TAB -> !isConfigPage() && mc.thePlayer.inventory.getItemStack() == null;
                case SKIN, SOUND -> mc.thePlayer.inventory.getItemStack() == null;
                case ContainerMaid.ACTION_HOME -> maid.ridingEntity == null && maid.riddenByEntity == null;
                default -> true;
            };
            button.topTab = button.id >= MAIN_TAB && button.id <= CONFIG_TAB;
            button.sideTab = button.id == BOOK || button.id == GLOBAL;
            button.offsetV = button.id == SCHEDULE ? 14 * maid.getSchedule()
                .ordinal() : 0;
            button.selected = switch (button.id) {
                case MAIN_TAB -> !isConfigPage();
                case CONFIG_TAB -> isConfigPage();
                case ContainerMaid.ACTION_HOME -> maid.getHomeArea()
                    .enabled();
                case ContainerMaid.ACTION_PICKUP_ALL -> maid.canPickupItems() || maid.canPickupExperience();
                case RIDE -> true;
                default -> false;
            };
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        switch (button.id) {
            case TASK_SWITCH -> taskListOpen = !taskListOpen;
            case TASK_CLOSE -> taskListOpen = false;
            case INFO -> {}
            case SKIN -> send(ContainerMaid.ACTION_MODELS);
            case SOUND -> send(ContainerMaid.ACTION_SOUNDS);
            case MAIN_TAB -> send(ContainerMaid.ACTION_ORIGINAL_MAIN);
            case CONFIG_TAB -> send(ContainerMaid.ACTION_CONFIG);
            case SCHEDULE -> send(
                ContainerMaid.ACTION_DAY + (maid.getSchedule()
                    .ordinal() + 1) % 3);
            default -> send(button.id);
        }
        refreshButtons();
    }

    private void send(int action) {
        mc.playerController.sendEnchantPacket(inventorySlots.windowId, action);
    }

    /** 开发探针复用真实按钮，不推定请求已被服务端接受 */
    public boolean requestAction(String action) {
        if (mc.currentScreen != this || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        int id = switch (action) {
            case "tasks" -> TASK_SWITCH;
            case "idle" -> ContainerMaid.ACTION_IDLE;
            case "attack" -> ContainerMaid.ACTION_ATTACK;
            case "ranged" -> ContainerMaid.ACTION_RANGED;
            case "schedule" -> SCHEDULE;
            case "home_toggle" -> ContainerMaid.ACTION_HOME;
            case "pickup" -> ContainerMaid.ACTION_PICKUP_ALL;
            case "open" -> SKIN;
            case "sound" -> SOUND;
            case "config" -> CONFIG_TAB;
            case "main_tab" -> MAIN_TAB;
            default -> throw new IllegalArgumentException("Unknown original GUI action");
        };
        for (var button : buttonList) if (button.id == id && button.visible && button.enabled) {
            actionPerformed(button);
            return true;
        }
        return false;
    }

    public boolean isTaskListOpen() {
        return taskListOpen;
    }

    public int getLastMouseX() {
        return lastMouseX;
    }

    public int getLastMouseY() {
        return lastMouseY;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BG);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, 256, 256);
        MaidGuiPreview.draw(maid, guiLeft, guiTop, mouseX, mouseY);
        mc.getTextureManager()
            .bindTexture(SIDE);
        for (int row = 0; row < 4; row++) {
            int y = guiTop + 113 + 11 * row;
            drawTexturedModalRect(guiLeft + 53, y, row * 9, 0, 9, 9);
            drawTexturedModalRect(guiLeft + 5, y, 0, 9, 47, 9);
            double ratio = switch (row) {
                case 0 -> maid.getHealth() / maid.getMaxHealth();
                case 1 -> maid.getTotalArmorValue() / 20.0;
                case 2 -> (maid.getExperience() % 120) / 120.0;
                default -> favorPercent();
            };
            drawTexturedModalRect(guiLeft + 7, y + 2, 2, 18 + row * 5, (int) (43 * Math.max(0, Math.min(1, ratio))), 5);
        }
        drawTexturedModalRect(guiLeft + 94, guiTop + 7, 107, 0, 149, 21);
        drawTexturedModalRect(guiLeft + 6, guiTop + 178, 0, 47, 67, 25);
        drawTexturedModalRect(guiLeft + 256, guiTop + 37, 235, 107, 21, 50);
        if (taskListOpen) {
            mc.getTextureManager()
                .bindTexture(TASK);
            drawTexturedModalRect(guiLeft - 93, guiTop + 5, 0, 0, 92, 251);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        int[] values = { (int) maid.getHealth(), maid.getTotalArmorValue(), maid.getExperience() / 120, favorLevel() };
        GL11.glPushMatrix();
        GL11.glScalef(0.5f, 0.5f, 1);
        for (int row = 0; row < 4; row++) fontRendererObj.drawString(
            formatScale(values[row]),
            126,
            (114 + 11 * row) * 2 + fontRendererObj.FONT_HEIGHT / 2,
            0x555555);
        GL11.glPopMatrix();
        if (taskListOpen) fontRendererObj.drawString("1/1", -48, 12, 0x333333);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        super.drawScreen(mouseX, mouseY, partialTick);
        drawCenteredString(
            fontRendererObj,
            "1.7.10-" + com.tianluoqaq.tlmnh.Tags.VERSION,
            guiLeft + 40,
            guiTop - 4,
            0xAAAAAA);
        drawTask(
            maid.getTask()
                .ordinal(),
            guiLeft + 6,
            guiTop + 161,
            guiLeft + 28,
            guiTop + 165,
            true);
        if (taskListOpen) for (int task = 0; task < 3; task++)
            drawTask(task, guiLeft - 87, guiTop + 25 + 19 * task, guiLeft - 66, guiTop + 29 + 19 * task, false);
        for (var raw : buttonList) {
            if (!(raw instanceof MaidAtlasButton button)) continue;
            if (button.contains(mouseX, mouseY)) {
                var lines = tooltip(button);
                if (!lines.isEmpty()) drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
                break;
            }
        }
    }

    private void drawTask(int task, int x, int y, int textX, int textY, boolean wrap) {
        RenderHelper.enableGUIStandardItemLighting();
        itemRender
            .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), new ItemStack(TASK_ICONS[task]), x, y);
        RenderHelper.disableStandardItemLighting();
        String title = text("task.touhou_little_maid." + TASK_KEYS[task]);
        if (wrap) fontRendererObj.drawSplitString(title, textX, textY, 42, 0x333333);
        else fontRendererObj.drawString(title, textX, textY, 0x333333);
    }

    private List<String> tooltip(MaidAtlasButton button) {
        if (button.id == BAUBLE) return Collections.singletonList(text("gui.tlmnh.original.bauble_excluded"));
        if (button.id <= ContainerMaid.ACTION_RANGED) return Collections.emptyList();
        var lines = new ArrayList<String>();
        if (button.id == TASK_SWITCH || button.id == TASK_CLOSE || button.id == 113 || button.id == 114) {
            String taskKey = switch (button.id) {
                case TASK_SWITCH -> "switch";
                case TASK_CLOSE -> "close";
                case 113 -> "previous_page";
                default -> "next_page";
            };
            lines.add(text("gui.touhou_little_maid.task." + taskKey));
        } else if (button.id == INFO) {
            lines.add(text("tooltips.touhou_little_maid.info.title"));
            lines.add(maid.getCommandSenderName());
            lines.add(maid.getModelId());
        } else if (button.id == SCHEDULE) {
            lines.add(
                text(
                    "gui.touhou_little_maid.schedule." + maid.getSchedule()
                        .name()
                        .toLowerCase(java.util.Locale.ROOT)));
        } else {
            String key = "gui.touhou_little_maid.button." + button.tooltip;
            if (button.id == ContainerMaid.ACTION_HOME || button.id == ContainerMaid.ACTION_PICKUP_ALL
                || button.id == RIDE) key += "." + button.selected;
            if (StatCollector.canTranslate(key)) lines.add(text(key));
            if (StatCollector.canTranslate("gui.touhou_little_maid.button." + button.tooltip + ".desc"))
                lines.add(text("gui.touhou_little_maid.button." + button.tooltip + ".desc"));
        }
        if (!button.enabled && button.id != MAIN_TAB && button.id != SKIN && button.id != 113 && button.id != 114)
            lines.add(text("gui.tlmnh.original.pending"));
        return lines;
    }

    private int favorLevel() {
        int favor = maid.getFavorability();
        return favor >= 384 ? 3 : favor >= 192 ? 2 : favor >= 64 ? 1 : 0;
    }

    private double favorPercent() {
        int favor = maid.getFavorability();
        return switch (favorLevel()) {
            case 0 -> favor / 64.0;
            case 1 -> (favor - 64) / 128.0;
            case 2 -> (favor - 192) / 192.0;
            default -> 0;
        };
    }

    private static String formatScale(long value) {
        if (value >= 1_000_000_000L) return unit(value, 1_000_000_000L, "G");
        if (value >= 1_000_000L) return unit(value, 1_000_000L, "M");
        if (value >= 1000L) return unit(value, 1000L, "K");
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private static String unit(long value, long divisor, String suffix) {
        long count = value / divisor;
        return count < 10 ? count + "." + (value % divisor / (divisor / 10)) + suffix : count + suffix;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
