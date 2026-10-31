/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/sound/MaidSoundPackGui.java, MIT
 * Adaptation: authoritative legacy container, builtin-only sound events and link confirmation
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.sound;

import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.SoundElementButton;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.SoundPackButton;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidSoundPackGui extends GuiScreen implements net.minecraft.client.gui.GuiYesNoCallback {

    private static final ResourceLocation ICON = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_custom_sound.png");
    private final ContainerMaidSounds container;
    private int guiLeft, guiTop;
    private String selectSoundId, pendingLink;
    private int packPage, soundPage;
    private MaidSoundInstance lastPreview;
    private String lastPreviewEvent = "";

    public MaidSoundPackGui(ContainerMaidSounds container) {

        this.container = container;

    }

    private static String text(String key) {
        return StatCollector.translateToLocal("gui.touhou_little_maid.custom_sound." + key);
    }

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - 400) / 2;
        guiTop = (height - 220) / 2;
        mc.thePlayer.openContainer = container;
        buttonList.clear();
        var packs = BuiltinSoundCatalog.packs();
        packPage = Math.max(0, Math.min(packPage, (packs.size() - 1) / 4));
        for (int i = packPage * 4; i < Math.min(packs.size(), packPage * 4 + 4); i++) {
            var info = packs.get(i);
            var button = new SoundPackButton(1000 + i, guiLeft + 5, guiTop + 19 + 45 * (i % 4), info);
            button.selected = info.id.equals(selectSoundId);
            button.inUse = info.id.equals(
                container.getMaid()
                    .getSoundPackId());
            buttonList.add(button);
        }
        add(1, 5, 201, 32, 16, "<", "");
        add(2, 203, 201, 32, 16, ">", "");
        var pack = BuiltinSoundCatalog.get(selectSoundId);
        if (pack == null) return;
        add(3, 245, 19, 110, 18, text("pack.apply"), "");
        add(4, 358, 19, 18, 18, "", "tooltips.touhou_little_maid.custom_sound.open_url");
        add(5, 379, 19, 18, 18, "", "tooltips.touhou_little_maid.custom_sound.credit");
        var events = new ArrayList<>(pack.events.entrySet());
        soundPage = Math.max(0, Math.min(soundPage, (events.size() - 1) / 13));
        for (int i = soundPage * 13; i < Math.min(events.size(), soundPage * 13 + 13); i++) {
            var event = events.get(i);
            buttonList.add(
                new SoundElementButton(
                    2000 + i,
                    guiLeft + 245,
                    guiTop + 41 + 12 * (i % 13),
                    event.getKey(),
                    event.getValue(),
                    i % 13 % 2 != 0));
        }
        add(6, 245, 201, 16, 16, "<", "");
        add(7, 381, 201, 16, 16, ">", "");
    }

    private void add(int id, int x, int y, int width, int height, String text, String tooltip) {
        var button = new FlatColorButton(id, guiLeft + x, guiTop + y, width, height, text);
        button.tooltip = tooltip;
        buttonList.add(button);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || mc.currentScreen != this
            || mc.thePlayer.openContainer != container
            || !container.canInteractWith(mc.thePlayer)) return;
        for (var raw : buttonList) ((FlatColorButton) raw).focused = raw == button;
        var packs = BuiltinSoundCatalog.packs();
        if (button.id >= 2000) {
            var pack = BuiltinSoundCatalog.get(selectSoundId);
            preview(new ArrayList<>(pack.events.keySet()).get(button.id - 2000));
        } else if (button.id >= 1000) {
            selectSoundId = packs.get(button.id - 1000).id;
            soundPage = 0;
            initGui();
        } else switch (button.id) {
            case 1 -> {
                if (packPage > 0) {
                    packPage--;
                    initGui();
                }
            }
            case 2 -> {
                if ((packPage + 1) * 4 < packs.size()) {
                    packPage++;
                    initGui();
                }
            }
            case 3 -> {
                if (mc.thePlayer.inventory.getItemStack() == null)
                    for (int i = 0; i < packs.size(); i++) if (packs.get(i).id.equals(selectSoundId))
                        mc.playerController.sendEnchantPacket(container.windowId, i);
                initGui();
            }
            case 4 -> {
                pendingLink = BuiltinSoundCatalog.get(selectSoundId).url;
                mc.displayGuiScreen(new GuiConfirmOpenLink(this, pendingLink, 0, true));
            }
            case 5 -> preview("maid.credit");
            case 6 -> {
                if (soundPage > 0) {
                    soundPage--;
                    initGui();
                }
            }
            case 7 -> {
                if ((soundPage + 1) * 13 < BuiltinSoundCatalog.get(selectSoundId).events.size()) {
                    soundPage++;
                    initGui();
                }
            }
            default -> {}
        }
    }

    private void preview(String event) {
        var pack = BuiltinSoundCatalog.get(selectSoundId);
        lastPreviewEvent = event;
        lastPreview = null;
        if (pack == null || pack.events.getOrDefault(event, 0) == 0) return;
        lastPreview = new MaidSoundInstance(selectSoundId, event, container.getMaid(), true);
        mc.getSoundHandler()
            .playSound(lastPreview);
    }

    @Override
    public void confirmClicked(boolean confirmed, int id) {
        if (confirmed && pendingLink != null) {
            try {
                java.awt.Desktop.getDesktop()
                    .browse(new java.net.URI(pendingLink));
            } catch (Exception error) {
                TLMNotHard.LOG.warn("Cannot open builtin sound credit page", error);
            }
        }
        pendingLink = null;
        mc.displayGuiScreen(this);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (mc.thePlayer.openContainer != container || !container.canInteractWith(mc.thePlayer)) {
            mc.thePlayer.closeScreen();
            return;
        }
        for (var raw : buttonList) if (raw instanceof SoundPackButton pack) pack.inUse = BuiltinSoundCatalog.packs()
            .get(pack.id - 1000).id.equals(
                container.getMaid()
                    .getSoundPackId());
    }

    private void drawPanels(float partialTick, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + 240, guiTop + 220, 0xFF2A2A2A);
        drawRect(guiLeft + 242, guiTop, guiLeft + 400, guiTop + 220, 0xFF2A2A2A);
        drawCenteredString(fontRendererObj, text("pack.title"), guiLeft + 120, guiTop + 6, 0xFFFFFF);
        drawCenteredString(fontRendererObj, text("sounds.preview"), guiLeft + 321, guiTop + 6, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            (packPage + 1) + "/"
                + ((BuiltinSoundCatalog.packs()
                    .size() - 1) / 4 + 1),
            guiLeft + 120,
            guiTop + 206,
            0xBBBBBB);
        var pack = BuiltinSoundCatalog.get(selectSoundId);
        if (pack != null) drawCenteredString(
            fontRendererObj,
            (soundPage + 1) + "/" + ((pack.events.size() - 1) / 13 + 1),
            guiLeft + 321,
            guiTop + 206,
            0xBBBBBB);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();
        drawPanels(partialTick, mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTick);
        if (BuiltinSoundCatalog.get(selectSoundId) != null) {
            GL11.glColor4f(1, 1, 1, 1);
            mc.getTextureManager()
                .bindTexture(ICON);
            drawTexturedModalRect(guiLeft + 359, guiTop + 20, 0, 0, 16, 16);
            drawTexturedModalRect(guiLeft + 380, guiTop + 20, 16, 0, 16, 16);
        }
        for (var raw : buttonList) if (raw instanceof FlatColorButton button && button.contains(mouseX, mouseY)) {
            String tooltip = button.tooltip;
            if (button instanceof SoundPackButton) tooltip = BuiltinModelNames.INSTANCE.text(
                BuiltinSoundCatalog.packs()
                    .get(button.id - 1000).description);
            else if (!tooltip.isEmpty()) tooltip = StatCollector.translateToLocal(tooltip);
            if (!tooltip.isEmpty())
                drawHoveringText(Collections.singletonList(tooltip), mouseX, mouseY, fontRendererObj);
        }
    }

    public ContainerMaidSounds getContainer() {
        return container;
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == 1 || key == mc.gameSettings.keyBindInventory.getKeyCode()) mc.thePlayer.closeScreen();
        else super.keyTyped(character, key);
    }

    public boolean pressButton(int id) {
        for (var button : buttonList) if (button.id == id && button.visible && button.enabled) {
            actionPerformed(button);
            return true;
        }
        return false;
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("selected", selectSoundId);
        result.addProperty(
            "applied",
            container.getMaid()
                .getSoundPackId());
        result.addProperty("packPage", packPage);
        result.addProperty("soundPage", soundPage);
        result.addProperty("previewEvent", lastPreviewEvent);
        result.addProperty(
            "previewPlaying",
            lastPreview != null && mc.getSoundHandler()
                .isSoundPlaying(lastPreview));
        var visible = new JsonArray();
        for (var raw : buttonList)
            if (raw instanceof SoundElementButton sound) visible.add(new com.google.gson.JsonPrimitive(sound.event));
        result.add("visibleEvents", visible);
        return result;
    }
}
