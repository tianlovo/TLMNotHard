package com.tianluoqaq.tlmnh.client.gui;

import com.tianluoqaq.tlmnh.common.model.BuiltinModelDataTuning;

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
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelSelection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 分页和筛选只改变可见列表，发给服务端的仍是固定全局索引 */
@SideOnly(Side.CLIENT)
public final class GuiMaidModels extends GuiContainer {



    private final ContainerMaidModels container;
    private final BuiltinModelNames names = BuiltinModelNames.INSTANCE;
    private final Set<GuiButton> ownButtons = Collections.newSetFromMap(new IdentityHashMap<>());
    private GuiTextField search;
    private List<Integer> matches;
    private int page, nameRevision = ClientGuiLayout.UNSET_OR_NOT_FOUND;
    private boolean winefoxOnly;

    public GuiMaidModels(ContainerMaidModels container) {
        super(container);
        this.container = container;
        xSize = ClientGuiLayout.GUI_MODELS_X_SIZE;
        ySize = ClientGuiLayout.GUI_MODELS_Y_SIZE;
    }

    @Override
    public void initGui() {
        String previousSearch = search == null ? "" : search.getText();
        super.initGui();
        search = new GuiTextField(fontRendererObj, guiLeft + ClientGuiLayout.GUI_MODELS_SEARCH, guiTop + ClientGuiLayout.IDLE_BTN_Y, ClientGuiLayout.GUI_MODELS_GUI_TEXT_FIELD_FOURTH_PARAM, ClientGuiLayout.GUI_TEXT_Y_2);
        search.setMaxStringLength(ClientGuiLayout.DAY_BTN_Y);
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
        int from = page * ClientGuiLayout.PAGE_SIZE;
        return Collections
            .unmodifiableList(new ArrayList<>(matches.subList(from, Math.min(from + ClientGuiLayout.PAGE_SIZE, matches.size()))));
    }

    public boolean changePage(int delta) {
        if (delta != ClientGuiLayout.UNSET_OR_NOT_FOUND && delta != ClientGuiLayout.SUBSTRING_START_OFFSET || page + delta < 0 || page + delta >= pages()) return false;
        page += delta;
        rebuildButtons();
        return true;
    }

    public void setWinefoxOnly(boolean enabled) {
        winefoxOnly = enabled;
        page = ClientGuiLayout.GUI_MODELS_PAGE;
        refreshMatches();
    }

    public boolean selectVisibleModel(int index) {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)
            || !getVisibleModelIndices().contains(index)) return false;
        var choice = BuiltinModelCatalog.choices()
            .get(index);
        if (choice.isEasterEgg()) return false;
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
            .sendEnchantPacket(inventorySlots.windowId, BuiltinModelSelection.wireAction(BuiltinModelDataTuning.BACK));
        return true;
    }

    private void refreshMatches() {
        nameRevision = names.getRevision();
        matches = BuiltinModelSelection.matching(winefoxOnly, search.getText(), names::name);
        page = Math.max(ClientGuiLayout.GUI_MODELS_PAGE, Math.min(page, pages() - 1));
        rebuildButtons();
    }

    private int pages() {
        return Math.max(ClientGuiLayout.SUBSTRING_START_OFFSET, (matches.size() + ClientGuiLayout.PAGE_SIZE - 1) / ClientGuiLayout.PAGE_SIZE);
    }

    private void rebuildButtons() {
        buttonList.clear();
        ownButtons.clear();
        addButton(new GuiButton(ClientGuiLayout.FILTER, guiLeft + ClientGuiLayout.FILTER_BTN_X, guiTop + ClientGuiLayout.FILTER_BTN_Y, ClientGuiLayout.PICKUP_ITEMS_BTN_Y, ClientGuiLayout.ACTION_BTN_HEIGHT, ""));
        int from = page * ClientGuiLayout.PAGE_SIZE;
        for (int row = 0; row < ClientGuiLayout.PAGE_SIZE && from + row < matches.size(); row++)
            addButton(new GuiButton(matches.get(from + row), guiLeft + ClientGuiLayout.GUI_TEXT_X, guiTop + ClientGuiLayout.GUI_MODELS_BTN_Y + row * ClientGuiLayout.GUI_MODELS_BTN_Y_2, ClientGuiLayout.GUI_MODELS_BTN_WIDTH, ClientGuiLayout.ACTION_BTN_HEIGHT, ""));
        addButton(
            new GuiButton(
                BuiltinModelDataTuning.BACK,
                guiLeft + ClientGuiLayout.GUI_TEXT_X,
                guiTop + ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM,
                ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM_2,
                ClientGuiLayout.ACTION_BTN_HEIGHT,
                text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_BACK)));
        addButton(new GuiButton(ClientGuiLayout.PREVIOUS, guiLeft + ClientGuiLayout.HOME_REST_BTN_Y, guiTop + ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM, ClientGuiLayout.PREVIOUS_BTN_WIDTH, ClientGuiLayout.ACTION_BTN_HEIGHT, ClientGuiIds.GUI_MODELS_GUI_BUTTON_MESSAGE));
        addButton(new GuiButton(ClientGuiLayout.NEXT, guiLeft + ClientGuiLayout.NEXT_BTN_X, guiTop + ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM, ClientGuiLayout.PREVIOUS_BTN_WIDTH, ClientGuiLayout.ACTION_BTN_HEIGHT, ClientGuiIds.GUI_MODELS_GUI_BUTTON_MESSAGE_2));
        refreshLabels();
    }

    private void addButton(GuiButton button) {
        ownButtons.add(button);
        buttonList.add(button);
    }

    private String label(BuiltinModelCatalog.Choice choice) {
        int variant = BuiltinModelSelection.extraTextureNumber(choice);
        return names.name(choice) + (variant == 0 ? ""
            : ClientGuiDiagnostics.GUI_MODELS_LABEL_MESSAGE + StatCollector.translateToLocalFormatted(ClientGuiIds.I18N_GUI_TLMNH_MODELS_EXTRA_TEXTURE, variant));
    }

    private void refreshLabels() {
        for (var button : buttonList) {
            if (!ownButtons.contains(button)) continue;
            if (button.id == ClientGuiLayout.FILTER) {
                button.displayString = text(winefoxOnly ? ClientGuiIds.I18N_GUI_TLMNH_MODELS_WINEFOX_ONLY : ClientGuiIds.I18N_GUI_TLMNH_MODELS_ALL);
            } else if (button.id == ClientGuiLayout.PREVIOUS) button.enabled = page > 0;
            else if (button.id == ClientGuiLayout.NEXT) button.enabled = page + 1 < pages();
            else if (button.id < BuiltinModelDataTuning.BACK) {
                var choice = BuiltinModelCatalog.choices()
                    .get(button.id);
                boolean selected = choice.getId()
                    .equals(
                        container.getMaid()
                            .getModelId());
                button.enabled = !selected;
                String prefix = selected ? text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_SELECTED) + ClientGuiDiagnostics.SINGLE_SPACE : "";
                button.displayString = fontRendererObj.trimStringToWidth(prefix + label(choice), ClientGuiLayout.GUI_MODELS_TRIM_STRING_TO_WIDTH_SECOND_PARAM);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || !ownButtons.contains(button)) return;
        switch (button.id) {
            case ClientGuiLayout.FILTER -> {
                setWinefoxOnly(!winefoxOnly);
            }
            case ClientGuiLayout.PREVIOUS -> {
                changePage(ClientGuiLayout.UNSET_OR_NOT_FOUND);
            }
            case ClientGuiLayout.NEXT -> {
                changePage(ClientGuiLayout.SUBSTRING_START_OFFSET);
            }
            case BuiltinModelDataTuning.BACK -> returnToMaid();
            default -> selectVisibleModel(button.id);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode != ClientGuiLayout.SUBSTRING_START_OFFSET && search.textboxKeyTyped(typedChar, keyCode)) {
            page = ClientGuiLayout.GUI_MODELS_PAGE;
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
            fontRendererObj.drawString(text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_SEARCH), guiLeft + ClientGuiLayout.GUI_RECT_TOP, guiTop + ClientGuiLayout.GUI_MODELS_TEXT_Y, ClientGuiLayout.RGB_GRAY);
        for (var button : buttonList) {
            if (!ownButtons.contains(button) || button.id >= BuiltinModelDataTuning.BACK
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
        fontRendererObj.drawString(text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_TITLE), ClientGuiLayout.GUI_TEXT_X, ClientGuiLayout.GUI_MODELS_TEXT_Y_2, ClientGuiLayout.RGB_COLOR_F4F1E9);
        if (matches.isEmpty()) fontRendererObj.drawString(text(ClientGuiIds.I18N_GUI_TLMNH_MODELS_EMPTY), ClientGuiLayout.GUI_MODELS_SEARCH, ClientGuiLayout.GUI_MODELS_TEXT_Y_3, ClientGuiLayout.RGB_COLOR_D2D8DD);
        String count = StatCollector
            .translateToLocalFormatted(ClientGuiIds.I18N_GUI_TLMNH_MODELS_PAGE, page + 1, pages(), matches.size());
        fontRendererObj.drawString(count, ClientGuiLayout.GUI_MODELS_DOMAIN_PARAM - fontRendererObj.getStringWidth(count) / ClientGuiLayout.MIDPOINT_DIVISOR, ClientGuiLayout.GUI_MODELS_TEXT_Y_4, ClientGuiLayout.RGB_COLOR_D2D8DD);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, ClientGuiLayout.ARGB_COLOR_FF25313C);
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
