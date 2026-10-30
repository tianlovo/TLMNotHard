package com.tianluoqaq.tlmnh.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.StatCollector;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelSelection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 分页和筛选只改变可见列表，发给服务端的仍是固定全局索引 */
@SideOnly(Side.CLIENT)
public final class GuiMaidModels extends GuiContainer {

    private static final int PAGE_SIZE = 7;
    private static final int FILTER = 1000, PREVIOUS = 1001, NEXT = 1002;
    private final ContainerMaidModels container;
    private final BuiltinModelNames names = BuiltinModelNames.INSTANCE;
    private final Set<GuiButton> ownButtons = Collections.newSetFromMap(new IdentityHashMap<>());
    private GuiTextField search;
    private List<Integer> matches;
    private int page, nameRevision = -1;
    private boolean winefoxOnly;

    public GuiMaidModels(ContainerMaidModels container) {
        super(container);
        this.container = container;
        xSize = 320;
        ySize = 232;
    }

    @Override
    public void initGui() {
        String previousSearch = search == null ? "" : search.getText();
        super.initGui();
        search = new GuiTextField(fontRendererObj, guiLeft + 12, guiTop + 28, 188, 18);
        search.setMaxStringLength(64);
        search.setText(previousSearch);
        search.setFocused(true);
        refreshMatches();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!container.canInteractWith(mc.thePlayer)) {
            mc.thePlayer.closeScreen();
            return;
        }
        search.updateCursorCounter();
        if (names.getRevision() != nameRevision) refreshMatches();
        else refreshLabels();
    }

    public EntityMaid getMaid() {
        return container.getMaid();
    }

    public List<Integer> getVisibleModelIndices() {
        if (matches == null) return Collections.emptyList();
        int from = page * PAGE_SIZE;
        return Collections
            .unmodifiableList(new ArrayList<>(matches.subList(from, Math.min(from + PAGE_SIZE, matches.size()))));
    }

    public boolean changePage(int delta) {
        if (delta != -1 && delta != 1 || page + delta < 0 || page + delta >= pages()) return false;
        page += delta;
        rebuildButtons();
        return true;
    }

    public void setWinefoxOnly(boolean enabled) {
        winefoxOnly = enabled;
        page = 0;
        refreshMatches();
    }

    public boolean selectVisibleModel(int index) {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)
            || !getVisibleModelIndices().contains(index)) return false;
        var choice = BuiltinModelCatalog.choices()
            .get(index);
        if (choice.getId()
            .equals(
                container.getMaid()
                    .getModelId()))
            return false;
        mc.playerController.sendEnchantPacket(inventorySlots.windowId, BuiltinModelSelection.wireAction(index));
        return true;
    }

    public boolean returnToMaid() {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)) return false;
        mc.playerController
            .sendEnchantPacket(inventorySlots.windowId, BuiltinModelSelection.wireAction(BuiltinModelSelection.BACK));
        return true;
    }

    private void refreshMatches() {
        nameRevision = names.getRevision();
        matches = BuiltinModelSelection.matching(winefoxOnly, search.getText(), names::name);
        page = Math.max(0, Math.min(page, pages() - 1));
        rebuildButtons();
    }

    private int pages() {
        return Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void rebuildButtons() {
        buttonList.clear();
        ownButtons.clear();
        addButton(new GuiButton(FILTER, guiLeft + 208, guiTop + 26, 100, 20, ""));
        int from = page * PAGE_SIZE;
        for (int row = 0; row < PAGE_SIZE && from + row < matches.size(); row++)
            addButton(new GuiButton(matches.get(from + row), guiLeft + 8, guiTop + 50 + row * 21, 304, 20, ""));
        addButton(
            new GuiButton(
                BuiltinModelSelection.BACK,
                guiLeft + 8,
                guiTop + 204,
                88,
                20,
                text("gui.tlmnh.models.back")));
        addButton(new GuiButton(PREVIOUS, guiLeft + 108, guiTop + 204, 22, 20, "<"));
        addButton(new GuiButton(NEXT, guiLeft + 286, guiTop + 204, 22, 20, ">"));
        refreshLabels();
    }

    private void addButton(GuiButton button) {
        ownButtons.add(button);
        buttonList.add(button);
    }

    private String label(BuiltinModelCatalog.Choice choice) {
        int variant = BuiltinModelSelection.extraTextureNumber(choice);
        return names.name(choice) + (variant == 0 ? ""
            : " / " + StatCollector.translateToLocalFormatted("gui.tlmnh.models.extra_texture", variant));
    }

    private void refreshLabels() {
        for (var button : buttonList) {
            if (!ownButtons.contains(button)) continue;
            if (button.id == FILTER) {
                button.displayString = text(winefoxOnly ? "gui.tlmnh.models.winefox_only" : "gui.tlmnh.models.all");
            } else if (button.id == PREVIOUS) button.enabled = page > 0;
            else if (button.id == NEXT) button.enabled = page + 1 < pages();
            else if (button.id < BuiltinModelSelection.BACK) {
                var choice = BuiltinModelCatalog.choices()
                    .get(button.id);
                boolean selected = choice.getId()
                    .equals(
                        container.getMaid()
                            .getModelId());
                button.enabled = !selected;
                String prefix = selected ? text("gui.tlmnh.models.selected") + " " : "";
                button.displayString = fontRendererObj.trimStringToWidth(prefix + label(choice), 292);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || !ownButtons.contains(button)) return;
        switch (button.id) {
            case FILTER -> {
                setWinefoxOnly(!winefoxOnly);
            }
            case PREVIOUS -> {
                changePage(-1);
            }
            case NEXT -> {
                changePage(1);
            }
            case BuiltinModelSelection.BACK -> returnToMaid();
            default -> selectVisibleModel(button.id);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode != 1 && search.textboxKeyTyped(typedChar, keyCode)) {
            page = 0;
            refreshMatches();
        } else super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        search.mouseClicked(mouseX, mouseY, button);
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        search.drawTextBox();
        if (search.getText()
            .isEmpty())
            fontRendererObj.drawString(text("gui.tlmnh.models.search"), guiLeft + 16, guiTop + 33, 0x808080);
        for (var button : buttonList) {
            if (!ownButtons.contains(button) || button.id >= BuiltinModelSelection.BACK
                || mouseX < button.xPosition
                || mouseY < button.yPosition
                || mouseX >= button.xPosition + button.width
                || mouseY >= button.yPosition + button.height) continue;
            var choice = BuiltinModelCatalog.choices()
                .get(button.id);
            drawHoveringText(Arrays.asList(label(choice), choice.getId()), mouseX, mouseY, fontRendererObj);
            break;
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(text("gui.tlmnh.models.title"), 8, 7, 0xF4F1E9);
        if (matches.isEmpty()) fontRendererObj.drawString(text("gui.tlmnh.models.empty"), 12, 62, 0xD2D8DD);
        String count = StatCollector
            .translateToLocalFormatted("gui.tlmnh.models.page", page + 1, pages(), matches.size());
        fontRendererObj.drawString(count, 204 - fontRendererObj.getStringWidth(count) / 2, 210, 0xD2D8DD);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xFF25313C);
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
