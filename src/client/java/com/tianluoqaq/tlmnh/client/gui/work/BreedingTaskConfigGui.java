package com.tianluoqaq.tlmnh.client.gui.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.StatCollector;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingConfigTuning;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.StatLogic;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData.TargetMode;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider.BreedingCandidateEntry;
import com.tianluoqaq.tlmnh.common.work.container.BreedingContainerProtocol;
import com.tianluoqaq.tlmnh.common.work.container.BreedingTaskConfigContainer;
import com.tianluoqaq.tlmnh.common.work.container.MaidBreedingConfigNetwork;
import com.tianluoqaq.tlmnh.common.work.container.MaidBreedingConfigPayload;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端编辑副本通过既有单次容器会话提交，切换页签与关闭时同样保存 */
@SideOnly(Side.CLIENT)
public final class BreedingTaskConfigGui extends AbstractMaidContainerGui<BreedingTaskConfigContainer> {

    private final List<BreedingCandidateEntry> candidates = new ArrayList<>();
    private final List<BreedingCandidateEntry> filtered = new ArrayList<>();
    private TargetMode loadedMode;
    private GuiTextField search;
    private String query = "";
    private int page;
    private boolean attributesPage, observedReady, sent;

    public BreedingTaskConfigGui(BreedingTaskConfigContainer container) {
        super(container);
    }

    @Override
    protected boolean isTaskConfigPage() {
        return true;
    }

    private BreedingTaskConfigData config() {
        return maidContainer.editedConfig();
    }

    private boolean canEdit() {
        return !sent && maidContainer.ready()
            && maidContainer.editable()
            && mc.thePlayer != null
            && mc.currentScreen == this
            && mc.thePlayer.openContainer == inventorySlots
            && inventorySlots.canInteractWith(mc.thePlayer)
            && mc.thePlayer.inventory.getItemStack() == null;
    }

    private boolean hasEdits() {
        return !sent && maidContainer.ready() && maidContainer.editable() && maidContainer.dirty();
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }

    private void refreshCandidates() {
        if (loadedMode != config().targetMode()) {
            loadedMode = config().targetMode();
            candidates.clear();
            candidates.addAll(
                CompatServices.cropsNhBreeding()
                    .listInWorldCandidates(config().isMutationPoolMode()));
            candidates.sort(
                Comparator.comparing(BreedingTaskConfigGui::name)
                    .thenComparing(entry -> entry.id));
        }
        String filter = query.trim()
            .toLowerCase(Locale.ROOT);
        filtered.clear();
        for (var entry : candidates) {
            if (entry.id.toLowerCase(Locale.ROOT)
                .contains(filter)
                || name(entry).toLowerCase(Locale.ROOT)
                    .contains(filter))
                filtered.add(entry);
        }
        page = Math.max(
            BreedingConfigGuiLayout.FIRST_PAGE,
            Math.min(
                page,
                Math.max(
                    BreedingConfigGuiLayout.FIRST_PAGE,
                    (filtered.size() - 1) / BreedingConfigGuiLayout.PAGE_SIZE)));
    }

    private static String name(BreedingCandidateEntry entry) {
        return StatCollector.translateToLocal(entry.unlocalizedName);
    }

    public List<BreedingCandidateEntry> visibleCandidates() {
        int start = page * BreedingConfigGuiLayout.PAGE_SIZE;
        return Collections.unmodifiableList(
            filtered.subList(start, Math.min(filtered.size(), start + BreedingConfigGuiLayout.PAGE_SIZE)));
    }

    @Override
    protected void initAdditionWidgets() {
        refreshCandidates();
        addButton(
            BreedingConfigGuiIds.MODE,
            BreedingConfigGuiLayout.LEFT,
            BreedingConfigGuiLayout.HEADER_TOP,
            BreedingConfigGuiLayout.MODE_WIDTH,
            BreedingConfigGuiLayout.HEADER_HEIGHT,
            text(config().isMutationPoolMode() ? BreedingConfigGuiIds.MODE_POOL : BreedingConfigGuiIds.MODE_CROP));
        addButton(
            BreedingConfigGuiIds.VIEW,
            BreedingConfigGuiLayout.VIEW_LEFT,
            BreedingConfigGuiLayout.HEADER_TOP,
            BreedingConfigGuiLayout.VIEW_WIDTH,
            BreedingConfigGuiLayout.HEADER_HEIGHT,
            text(attributesPage ? BreedingConfigGuiIds.TARGETS : BreedingConfigGuiIds.ATTRIBUTES));
        addButton(
            BreedingConfigGuiIds.SAVE,
            BreedingConfigGuiLayout.SAVE_LEFT,
            BreedingConfigGuiLayout.HEADER_TOP,
            BreedingConfigGuiLayout.SAVE_WIDTH,
            BreedingConfigGuiLayout.HEADER_HEIGHT,
            text(sent ? BreedingConfigGuiIds.SUBMITTED : BreedingConfigGuiIds.SAVE_KEY));
        if (attributesPage) {
            for (int row = 0; row < BreedingConfigGuiLayout.STAT_COUNT; row++) {
                for (int step = 0; step < BreedingConfigGuiLayout.STEP_COUNT; step++) {
                    addButton(
                        BreedingConfigGuiIds.FIRST_STAT + row * BreedingConfigGuiLayout.STEP_COUNT + step,
                        BreedingConfigGuiLayout.STAT_LEFT
                            + step * (BreedingConfigGuiLayout.STEP_WIDTH + BreedingConfigGuiLayout.STEP_GAP),
                        BreedingConfigGuiLayout.STAT_BUTTON_TOP + row * BreedingConfigGuiLayout.STAT_INTERVAL,
                        BreedingConfigGuiLayout.STEP_WIDTH,
                        BreedingConfigGuiLayout.STEP_HEIGHT,
                        BreedingConfigGuiIds.STEP_LABELS[step]);
                }
            }
            addButton(
                BreedingConfigGuiIds.LOGIC,
                BreedingConfigGuiLayout.LOGIC_LEFT,
                BreedingConfigGuiLayout.LOGIC_TOP,
                BreedingConfigGuiLayout.LOGIC_WIDTH,
                BreedingConfigGuiLayout.OPTION_HEIGHT,
                (config().max31Mode() ? StatLogic.AND : config().statLogic()).name());
            addButton(
                BreedingConfigGuiIds.MAX31,
                BreedingConfigGuiLayout.MAX31_LEFT,
                BreedingConfigGuiLayout.LOGIC_TOP,
                BreedingConfigGuiLayout.MAX31_WIDTH,
                BreedingConfigGuiLayout.OPTION_HEIGHT,
                text(config().max31Mode() ? BreedingConfigGuiIds.MAX31_ON : BreedingConfigGuiIds.MAX31_OFF));
        } else {
            boolean focused = search != null && search.isFocused();
            search = new GuiTextField(
                fontRendererObj,
                guiLeft + BreedingConfigGuiLayout.SEARCH_LEFT,
                guiTop + BreedingConfigGuiLayout.SEARCH_TOP,
                BreedingConfigGuiLayout.SEARCH_WIDTH,
                BreedingConfigGuiLayout.SEARCH_HEIGHT);
            search.setMaxStringLength(BreedingConfigTuning.MAX_TARGET_ID_LENGTH);
            search.setText(query);
            search.setFocused(focused);
            addButton(
                BreedingConfigGuiIds.PREVIOUS,
                BreedingConfigGuiLayout.PREVIOUS_LEFT,
                BreedingConfigGuiLayout.SEARCH_TOP,
                BreedingConfigGuiLayout.PAGE_BUTTON_WIDTH,
                BreedingConfigGuiLayout.SEARCH_HEIGHT,
                BreedingConfigGuiIds.PREVIOUS_LABEL);
            addButton(
                BreedingConfigGuiIds.NEXT,
                BreedingConfigGuiLayout.NEXT_LEFT,
                BreedingConfigGuiLayout.SEARCH_TOP,
                BreedingConfigGuiLayout.PAGE_BUTTON_WIDTH,
                BreedingConfigGuiLayout.SEARCH_HEIGHT,
                BreedingConfigGuiIds.NEXT_LABEL);
            for (int row = 0; row < visibleCandidates().size(); row++) {
                addButton(
                    BreedingConfigGuiIds.FIRST_CANDIDATE + row,
                    BreedingConfigGuiLayout.LEFT,
                    BreedingConfigGuiLayout.ROW_TOP + row * BreedingConfigGuiLayout.ROW_HEIGHT,
                    BreedingConfigGuiLayout.WIDTH,
                    BreedingConfigGuiLayout.ROW_HEIGHT,
                    "");
            }
        }
        refreshWidgets();
    }

    private void addButton(int id, int x, int y, int width, int height, String label) {
        buttonList.add(
            new GuiButton(
                id,
                guiLeft + x,
                guiTop + y,
                width,
                height,
                fontRendererObj.trimStringToWidth(label, width - BreedingConfigGuiLayout.LABEL_PADDING)));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!observedReady && maidContainer.ready()) {
            observedReady = true;
            initGui();
        }
        if (!attributesPage && search != null) search.updateCursorCounter();
        refreshWidgets();
    }

    private void refreshWidgets() {
        for (Object raw : buttonList) {
            if (!(raw instanceof GuiButton button) || button.id < BreedingConfigGuiIds.MODE) continue;
            button.enabled = switch (button.id) {
                case BreedingConfigGuiIds.VIEW -> true;
                case BreedingConfigGuiIds.PREVIOUS -> page > BreedingConfigGuiLayout.FIRST_PAGE;
                case BreedingConfigGuiIds.NEXT -> (page + 1) * BreedingConfigGuiLayout.PAGE_SIZE < filtered.size();
                case BreedingConfigGuiIds.SAVE -> canEdit() && hasEdits();
                case BreedingConfigGuiIds.LOGIC -> canEdit() && !config().max31Mode();
                default -> canEdit() && (button.id < BreedingConfigGuiIds.FIRST_STAT || !config().max31Mode());
            };
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < BreedingConfigGuiIds.MODE) {
            super.actionPerformed(button);
            return;
        }
        if (!button.enabled) return;
        switch (button.id) {
            case BreedingConfigGuiIds.VIEW -> attributesPage = !attributesPage;
            case BreedingConfigGuiIds.PREVIOUS -> page--;
            case BreedingConfigGuiIds.NEXT -> page++;
            case BreedingConfigGuiIds.SAVE -> submit(BreedingContainerProtocol.NO_NEXT_ACTION);
            case BreedingConfigGuiIds.MODE -> {
                config().setTargetMode(config().isMutationPoolMode() ? TargetMode.CROP : TargetMode.MUTATION_POOL);
                config().setTargetId("");
                maidContainer.markEdited();
                page = BreedingConfigGuiLayout.FIRST_PAGE;
            }
            case BreedingConfigGuiIds.LOGIC -> {
                config().setStatLogic(config().statLogic() == StatLogic.AND ? StatLogic.OR : StatLogic.AND);
                maidContainer.markEdited();
            }
            case BreedingConfigGuiIds.MAX31 -> {
                config().setMax31Mode(!config().max31Mode());
                maidContainer.markEdited();
            }
            default -> {
                if (button.id >= BreedingConfigGuiIds.FIRST_STAT) {
                    int offset = button.id - BreedingConfigGuiIds.FIRST_STAT;
                    changeStat(
                        offset / BreedingConfigGuiLayout.STEP_COUNT,
                        BreedingConfigGuiLayout.STEPS[offset % BreedingConfigGuiLayout.STEP_COUNT]);
                } else {
                    config().setTargetId(visibleCandidates().get(button.id - BreedingConfigGuiIds.FIRST_CANDIDATE).id);
                }
                maidContainer.markEdited();
            }
        }
        initGui();
    }

    private int statValue(int row) {
        if (config().max31Mode()) return BreedingConfigTuning.MAX_STAT;
        return switch (row) {
            case BreedingConfigGuiLayout.GAIN_ROW -> config().targetGain();
            case BreedingConfigGuiLayout.GROWTH_ROW -> config().targetGrowth();
            default -> config().targetResistance();
        };
    }

    private void changeStat(int row, int delta) {
        switch (row) {
            case BreedingConfigGuiLayout.GAIN_ROW -> config().setTargetGain(config().targetGain() + delta);
            case BreedingConfigGuiLayout.GROWTH_ROW -> config().setTargetGrowth(config().targetGrowth() + delta);
            default -> config().setTargetResistance(config().targetResistance() + delta);
        }
    }

    private void submit(int nextAction) {
        if (!hasEdits()) return;
        sent = true;
        MaidBreedingConfigNetwork.sendSubmit(MaidBreedingConfigPayload.from(maidContainer, config(), true), nextAction);
    }

    public boolean isAttributesPage() {
        return attributesPage;
    }

    public boolean hasSubmitted() {
        return sent;
    }

    public boolean setSearchText(String value) {
        if (attributesPage || value == null
            || value.length() > BreedingConfigTuning.MAX_TARGET_ID_LENGTH
            || mc.currentScreen != this) return false;
        query = value;
        page = BreedingConfigGuiLayout.FIRST_PAGE;
        initGui();
        return true;
    }

    public boolean pressButton(int id) {
        if (mc.currentScreen != this || mc.thePlayer == null
            || mc.thePlayer.openContainer != inventorySlots
            || !inventorySlots.canInteractWith(mc.thePlayer)) return false;
        for (Object raw : buttonList) {
            if (raw instanceof GuiButton button && button.id == id && button.visible && button.enabled) {
                actionPerformed(button);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void send(int action) {
        if (hasEdits() && MaidBreedingConfigNetwork.navigates(action)) submit(action);
        else super.send(action);
    }

    @Override
    public void onGuiClosed() {
        if (isOpeningLocalScreen()) return;
        submit(BreedingContainerProtocol.NO_NEXT_ACTION);
        super.onGuiClosed();
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (!attributesPage && search.textboxKeyTyped(character, key)) {
            setSearchText(search.getText());
        } else super.keyTyped(character, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (!attributesPage) search.mouseClicked(x, y, button);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        drawRect(
            guiLeft + BreedingConfigGuiLayout.PANEL_LEFT,
            guiTop + BreedingConfigGuiLayout.PANEL_TOP,
            guiLeft + BreedingConfigGuiLayout.PANEL_RIGHT,
            guiTop + BreedingConfigGuiLayout.PANEL_BOTTOM,
            BreedingConfigGuiLayout.BORDER_COLOR);
        drawRect(
            guiLeft + BreedingConfigGuiLayout.PANEL_LEFT + 1,
            guiTop + BreedingConfigGuiLayout.PANEL_TOP + 1,
            guiLeft + BreedingConfigGuiLayout.PANEL_RIGHT - 1,
            guiTop + BreedingConfigGuiLayout.PANEL_BOTTOM - 1,
            BreedingConfigGuiLayout.BACKGROUND_COLOR);
    }

    @Override
    protected void renderAddition(float partialTick, int mouseX, int mouseY) {
        if (attributesPage) {
            String target = config().hasTarget() ? config().targetId() : text(BreedingConfigGuiIds.NO_TARGET);
            for (var entry : candidates) if (entry.id.equals(config().targetId())) target = name(entry);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(target, BreedingConfigGuiLayout.TARGET_WIDTH),
                guiLeft + BreedingConfigGuiLayout.TARGET_LEFT,
                guiTop + BreedingConfigGuiLayout.TARGET_TOP,
                BreedingConfigGuiLayout.TEXT_COLOR);
            for (int row = 0; row < BreedingConfigGuiLayout.STAT_COUNT; row++) {
                int y = guiTop + BreedingConfigGuiLayout.STAT_LABEL_TOP + row * BreedingConfigGuiLayout.STAT_INTERVAL;
                fontRendererObj.drawString(
                    text(BreedingConfigGuiIds.STAT_KEYS[row]),
                    guiLeft + BreedingConfigGuiLayout.STAT_LEFT,
                    y,
                    BreedingConfigGuiLayout.TEXT_COLOR);
                String value = Integer.toString(statValue(row));
                fontRendererObj.drawString(
                    value,
                    guiLeft + BreedingConfigGuiLayout.STAT_VALUE_RIGHT - fontRendererObj.getStringWidth(value),
                    y,
                    BreedingConfigGuiLayout.TEXT_COLOR);
            }
            return;
        }
        search.drawTextBox();
        if (query.isEmpty() && !search.isFocused()) fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(
                text(BreedingConfigGuiIds.SEARCH),
                BreedingConfigGuiLayout.SEARCH_WIDTH - BreedingConfigGuiLayout.LABEL_PADDING),
            guiLeft + BreedingConfigGuiLayout.SEARCH_LEFT + BreedingConfigGuiLayout.SEARCH_TEXT_PADDING,
            guiTop + BreedingConfigGuiLayout.SEARCH_TOP + BreedingConfigGuiLayout.SEARCH_TEXT_TOP,
            BreedingConfigGuiLayout.PLACEHOLDER_COLOR);
        int pages = Math.max(
            BreedingConfigGuiLayout.MIN_PAGE_COUNT,
            (filtered.size() + BreedingConfigGuiLayout.PAGE_SIZE - 1) / BreedingConfigGuiLayout.PAGE_SIZE);
        drawCenteredString(
            fontRendererObj,
            (page + 1) + BreedingConfigGuiIds.PAGE_SEPARATOR + pages,
            guiLeft + BreedingConfigGuiLayout.PAGE_CENTER,
            guiTop + BreedingConfigGuiLayout.PAGE_TEXT_TOP,
            BreedingConfigGuiLayout.TEXT_COLOR);
        var visible = visibleCandidates();
        if (visible.isEmpty()) fontRendererObj.drawSplitString(
            text(BreedingConfigGuiIds.EMPTY),
            guiLeft + BreedingConfigGuiLayout.LEFT,
            guiTop + BreedingConfigGuiLayout.EMPTY_TOP,
            BreedingConfigGuiLayout.WIDTH,
            BreedingConfigGuiLayout.TEXT_COLOR);
        for (int row = 0; row < visible.size(); row++) {
            var entry = visible.get(row);
            int y = guiTop + BreedingConfigGuiLayout.ROW_TOP + row * BreedingConfigGuiLayout.ROW_HEIGHT;
            if (entry.iconStack != null) {
                RenderHelper.enableGUIStandardItemLighting();
                itemRender.renderItemAndEffectIntoGUI(
                    fontRendererObj,
                    mc.getTextureManager(),
                    entry.iconStack,
                    guiLeft + BreedingConfigGuiLayout.ICON_LEFT,
                    y + BreedingConfigGuiLayout.ICON_TOP_OFFSET);
                RenderHelper.disableStandardItemLighting();
            }
            boolean selected = entry.id.equals(config().targetId());
            String title = (selected ? BreedingConfigGuiIds.SELECTED_PREFIX : "") + name(entry);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(title, BreedingConfigGuiLayout.ROW_TEXT_WIDTH),
                guiLeft + BreedingConfigGuiLayout.ROW_TEXT_LEFT,
                y + BreedingConfigGuiLayout.ROW_TEXT_TOP_OFFSET,
                selected ? BreedingConfigGuiLayout.SELECTED_COLOR : BreedingConfigGuiLayout.TEXT_COLOR);
        }
    }

    @Override
    protected void renderAdditionTooltips(int mouseX, int mouseY) {
        if (attributesPage) {
            if (mouseX >= guiLeft + BreedingConfigGuiLayout.LOGIC_LEFT
                && mouseX < guiLeft + BreedingConfigGuiLayout.LOGIC_LEFT + BreedingConfigGuiLayout.LOGIC_WIDTH
                && mouseY >= guiTop + BreedingConfigGuiLayout.LOGIC_TOP
                && mouseY < guiTop + BreedingConfigGuiLayout.LOGIC_TOP + BreedingConfigGuiLayout.OPTION_HEIGHT)
                drawHoveringText(
                    Collections.singletonList(
                        text(
                            config().statLogic() == StatLogic.AND || config().max31Mode()
                                ? BreedingConfigGuiIds.LOGIC_AND
                                : BreedingConfigGuiIds.LOGIC_OR)),
                    mouseX,
                    mouseY,
                    fontRendererObj);
            return;
        }
        var visible = visibleCandidates();
        for (int row = 0; row < visible.size(); row++) {
            int top = guiTop + BreedingConfigGuiLayout.ROW_TOP + row * BreedingConfigGuiLayout.ROW_HEIGHT;
            if (mouseX >= guiLeft + BreedingConfigGuiLayout.LEFT
                && mouseX < guiLeft + BreedingConfigGuiLayout.LEFT + BreedingConfigGuiLayout.WIDTH
                && mouseY >= top
                && mouseY < top + BreedingConfigGuiLayout.ROW_HEIGHT) {
                var entry = visible.get(row);
                var lines = new ArrayList<String>();
                lines.add(name(entry));
                lines.add(entry.id);
                drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
                return;
            }
        }
    }
}
