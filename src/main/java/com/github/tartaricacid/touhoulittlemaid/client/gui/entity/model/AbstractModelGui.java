/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/model/AbstractModelGui.java, MIT
 * Adaptation: native 1.7.10 screen/input APIs, fixed builtin packs and existing container transport
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.BuiltinMaidPacks;
import com.tianluoqaq.tlmnh.client.gui.BuiltinMaidPacks.Pack;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.gui.MaidAtlasButton;
import com.tianluoqaq.tlmnh.client.gui.MaidCacheCheckbox;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelSelection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public abstract class AbstractModelGui extends GuiScreen {

    protected static final ResourceLocation BG = texture("skin_select");
    protected static final ResourceLocation SIDE = texture("skin_select_side");
    private static final ResourceLocation EMPTY_ICON = texture("empty_model_pack_icon");
    private static final int CHOICE = 10000, TAB = 2000, PREVIOUS = 2010, NEXT = 2011, UP = 2012, DOWN = 2013;
    private static final int CLOSE = 2014, SEARCH = 2015, CACHE = 2016;
    protected final ContainerMaidModels container;
    protected final EntityMaid entity;
    protected final BuiltinModelNames names = BuiltinModelNames.INSTANCE;
    protected final List<Pack> packs = BuiltinMaidPacks.packs();
    private final SkinGuiNumber guiNumber = new SkinGuiNumber(packs);
    private final Map<ResourceLocation, Integer> iconFrames = new HashMap<>();
    private GuiTextField searchBox;
    private String searchText = "";
    private boolean searchMode;
    private List<Integer> display = Collections.emptyList();
    private int namesRevision = -1, lastMouseX, lastMouseY;

    protected AbstractModelGui(ContainerMaidModels container) {
        this.container = container;
        this.entity = container.getMaid();
        setPackIndex(Math.max(0, Math.min(packs.size() - 1, getPackIndex())));
    }

    protected abstract int getPackIndex();

    protected abstract void setPackIndex(int index);

    protected abstract int getPageIndex();

    protected abstract void setPageIndex(int index);

    protected abstract int getRowIndex();

    protected abstract void setRowIndex(int index);

    protected abstract void drawLeftEntity(int middleX, int middleY, int mouseX, int mouseY);

    protected abstract void drawRightEntity(int x, int y, int modelIndex);

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("touhou_little_maid", "textures/gui/" + name + ".png");
    }

    @Override
    public void initGui() {
        boolean focused = searchBox != null && searchBox.isFocused();
        super.initGui();
        // FML OpenGuiHandler assigns the real window ID after this screen installs its container
        mc.thePlayer.openContainer = container;
        buttonList.clear();
        refreshDisplay();
        setRowIndex(Math.max(0, Math.min(maxRow(), getRowIndex())));
        int x = width / 2 + 50, y = height / 2;
        int tabs = guiNumber.getTabSize(getPackIndex());
        for (int i = 0; i < tabs; i++) {
            boolean selected = i == guiNumber.getTabIndex(getPackIndex()) && !searchMode;
            atlas(
                TAB + i,
                x - 98 + 28 * i,
                y - (selected ? 108 : 105),
                28,
                selected ? 31 : 25,
                BG,
                116,
                selected ? 224 : 194,
                0).enabled = !selected;
        }
        atlas(CLOSE, x + 122, y - 97, 21, 17, BG, 58, 201, 18);
        var previous = new GuiButton(PREVIOUS, x - 119, y - 101, 20, 20, "<");
        var next = new GuiButton(NEXT, x + 99, y - 101, 20, 20, ">");
        previous.enabled = getPageIndex() > 0;
        next.enabled = getPageIndex() < guiNumber.getPageSize() - 1;
        buttonList.add(previous);
        buttonList.add(next);
        atlas(UP, x + 125, y - 73, 14, 10, SIDE, 24, 15, 10);
        atlas(DOWN, x + 125, y + 83, 14, 10, SIDE, 38, 15, 10);
        atlas(
            SEARCH,
            x - 121,
            y + (searchMode ? 96 : 100),
            24,
            searchMode ? 26 : 21,
            BG,
            145,
            searchMode ? 194 : 220,
            0).enabled = !searchMode;
        var visible = getVisibleModelIndices();
        for (int i = 0; i < visible.size(); i++)
            atlas(CHOICE + visible.get(i), x - 108 + 20 * (i % 11), y - 61 + 30 * (i / 11), 15, 24, BG, 41, 201, 24);
        String label = text("gui.touhou_little_maid.skin.enable_cache");
        int cacheX = (x - 128) / 2 - (fontRendererObj.getStringWidth(label) + 20) / 2;
        var checkbox = new MaidCacheCheckbox(CACHE, cacheX, y - 101, label);
        buttonList.add(checkbox);
        searchBox = new GuiTextField(fontRendererObj, x - 96, y + 101, 216, 18);
        searchBox.setMaxStringLength(48);
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setText(searchText);
        searchBox.setFocused(searchMode && focused);
        namesRevision = names.getRevision();
    }

    private MaidAtlasButton atlas(int id, int x, int y, int width, int height, ResourceLocation resource, int u, int v,
        int hover) {
        var button = new MaidAtlasButton(id, x, y, width, height, resource, u, v, hover, 0, "");
        buttonList.add(button);
        return button;
    }

    private void refreshDisplay() {
        var matches = new ArrayList<Integer>();
        if (!searchMode) matches.addAll(packs.get(getPackIndex()).modelIndices);
        else {
            String keyword = searchText.toLowerCase(Locale.ENGLISH)
                .trim();
            var choices = BuiltinModelCatalog.choices();
            for (int i = 0; i < choices.size(); i++) {
                var choice = choices.get(i);
                boolean match = keyword.isEmpty() || names.name(choice)
                    .toLowerCase(Locale.ENGLISH)
                    .contains(keyword)
                    || choice.getId()
                        .toLowerCase(Locale.ENGLISH)
                        .contains(keyword);
                for (String line : choice.getDescription()) match |= names.text(line)
                    .toLowerCase(Locale.ENGLISH)
                    .contains(keyword);
                if (match) matches.add(i);
            }
        }
        display = matches;
    }

    private int maxRow() {
        return Math.max(0, (display.size() + 10) / 11 - 5);
    }

    public List<Integer> getVisibleModelIndices() {
        int from = Math.min(display.size(), guiNumber.modelFromIndex(getRowIndex()));
        return Collections
            .unmodifiableList(new ArrayList<>(display.subList(from, Math.min(from + 55, display.size()))));
    }

    public ContainerMaidModels getContainer() {
        return container;
    }

    public String getPackId() {
        return packs.get(getPackIndex()).id;
    }

    public int getRow() {
        return getRowIndex();
    }

    public String getSearchText() {
        return searchText;
    }

    public boolean isSearchMode() {
        return searchMode;
    }

    public int getLastMouseX() {
        return lastMouseX;
    }

    public int getLastMouseY() {
        return lastMouseY;
    }

    public boolean selectVisibleModel(int index) {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)
            || mc.thePlayer.inventory.getItemStack() != null
            || !getVisibleModelIndices().contains(index)) return false;
        mc.playerController.sendEnchantPacket(container.windowId, BuiltinModelSelection.wireAction(index));
        return true;
    }

    public boolean openDetails(int index) {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)
            || mc.thePlayer.inventory.getItemStack() != null
            || !getVisibleModelIndices().contains(index)) return false;
        mc.displayGuiScreen(
            new com.github.tartaricacid.touhoulittlemaid.client.gui.entity.detail.MaidModelDetailsGui(
                container,
                BuiltinModelCatalog.choices()
                    .get(index)));
        return true;
    }

    public boolean returnToMaid() {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)) return false;
        mc.playerController
            .sendEnchantPacket(container.windowId, BuiltinModelSelection.wireAction(BuiltinModelSelection.BACK));
        return true;
    }

    public boolean changeRow(int delta) {
        int next = Math.max(0, Math.min(maxRow(), getRowIndex() + delta));
        if (next == getRowIndex()) return false;
        setRowIndex(next);
        initGui();
        return true;
    }

    public boolean selectPack(String id) {
        for (int i = 0; i < packs.size(); i++) if (packs.get(i).id.equals(id)) {
            setPackIndex(i);
            setPageIndex(i / 7);
            setRowIndex(0);
            searchMode = false;
            searchText = "";
            initGui();
            return true;
        }
        return false;
    }

    public void search(String text) {
        searchMode = true;
        searchText = text.length() > 48 ? text.substring(0, 48) : text;
        setRowIndex(0);
        initGui();
        searchBox.setFocused(true);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        if (button.id >= CHOICE) {
            if (isShiftKeyDown()) openDetails(button.id - CHOICE);
            else selectVisibleModel(button.id - CHOICE);
        } else if (button.id >= TAB && button.id < TAB + 7) {
            selectPack(packs.get(guiNumber.tabToPackIndex(button.id - TAB, getPageIndex())).id);
        } else switch (button.id) {
            case CLOSE -> returnToMaid();
            case CACHE -> toggleIconCache();
            case UP -> changeRow(-1);
            case DOWN -> changeRow(1);
            case SEARCH -> search("");
            case PREVIOUS, NEXT -> {
                setPageIndex(
                    Math.max(0, Math.min(guiNumber.getPageSize() - 1, getPageIndex() + (button.id == NEXT ? 1 : -1))));
                selectPack(packs.get(guiNumber.tabToPackIndex(0, getPageIndex())).id);
            }
            default -> {}
        }
    }

    public void toggleIconCache() {
        MaidClientPreferences.setModelIconCache(!MaidClientPreferences.modelIconCache());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!container.canInteractWith(mc.thePlayer) || mc.thePlayer.openContainer != container) {
            mc.thePlayer.closeScreen();
            return;
        }
        searchBox.updateCursorCounter();
        if (names.getRevision() != namesRevision) {
            iconFrames.clear();
            initGui();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        int x = width / 2 + 50, y = height / 2;
        drawDefaultBackground();
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BG);
        drawTexturedModalRect(x - 128, y - 80, 0, 0, 256, 180);
        mc.getTextureManager()
            .bindTexture(SIDE);
        drawTexturedModalRect(x + 122, y - 80, 0, 0, 24, 180);
        float scroll = maxRow() == 0 ? 0 : (float) getRowIndex() / maxRow();
        boolean more = display.size() - 11 * getRowIndex() > 55;
        drawTexturedModalRect(x + 126, y - 61 + (int) (127 * scroll), more ? 24 : 36, 0, 12, 15);
        super.drawScreen(mouseX, mouseY, partialTick);
        drawTabIcons(x, y);
        drawLeftEntity(x, y, mouseX, mouseY);
        var visible = getVisibleModelIndices();
        for (int i = 0; i < visible.size(); i++)
            drawRightEntity(x - 100 + 20 * (i % 11), y - 38 + 30 * (i / 11), visible.get(i));
        if (!searchMode) drawPackInfo(packs.get(getPackIndex()), x, y);
        else {
            searchBox.drawTextBox();
            if (searchText.isEmpty() && !searchBox.isFocused())
                fontRendererObj.drawString(text("gui.touhou_little_maid.skin.search.hint"), x - 92, y + 106, 0x555555);
        }
        drawTooltips(mouseX, mouseY, x, y);
    }

    private void drawTabIcons(int x, int y) {
        for (int i = 0; i < guiNumber.getTabSize(getPackIndex()); i++) {
            var icon = packs.get(guiNumber.tabToPackIndex(i, getPageIndex())).icon;
            int frames = iconFrames.computeIfAbsent(icon, resource -> {
                try (var stream = mc.getResourceManager()
                    .getResource(resource)
                    .getInputStream()) {
                    var image = ImageIO.read(stream);
                    return image == null ? 0 : Math.max(1, image.getHeight() / image.getWidth());
                } catch (IOException error) {
                    return 0;
                }
            });
            if (frames == 0) {
                icon = EMPTY_ICON;
                frames = 1;
            }
            mc.getTextureManager()
                .bindTexture(icon);
            GL11.glColor4f(1, 1, 1, 1);
            int frame = ((int) System.currentTimeMillis() / 50 / 2) % frames;
            func_146110_a(x - 92 + 28 * i, y - 98, 0, frame * 16, 16, 16, 16, 16 * frames);
        }
    }

    private void drawPackInfo(Pack pack, int x, int y) {
        int center = (x - 128) / 2, lineY = y - 80;
        for (String line : fontRendererObj.listFormattedStringToWidth(names.text(pack.name), x - 148)) {
            lineY += 10;
            drawCenteredString(fontRendererObj, line, center, lineY, 0xFFFFFF);
        }
        for (String raw : pack.description)
            for (String line : fontRendererObj.listFormattedStringToWidth(names.text(raw), x - 148)) {
                lineY += 10;
                drawCenteredString(fontRendererObj, line, center, lineY, 0x555555);
            }
        for (int i = 0; i < pack.authors.size(); i += 2) {
            lineY += 10;
            drawCenteredString(
                fontRendererObj,
                pack.authors.subList(i, Math.min(i + 2, pack.authors.size()))
                    .toString(),
                center,
                lineY,
                0xFFAA00);
        }
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocalFormatted("gui.touhou_little_maid.skin.text.version", pack.version),
            center,
            lineY + 10,
            0x00AAAA);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocalFormatted("gui.touhou_little_maid.skin.text.date", pack.date),
            center,
            lineY + 20,
            0x55FF55);
        drawCenteredString(fontRendererObj, (getPageIndex() + 1) + "/" + guiNumber.getPageSize(), x, y - 118, 0xFFFFFF);
    }

    private void drawTooltips(int mouseX, int mouseY, int x, int y) {
        for (var button : buttonList) if (mouseX >= button.xPosition && mouseY >= button.yPosition
            && mouseX < button.xPosition + button.width
            && mouseY < button.yPosition + button.height) {
                var lines = new ArrayList<String>();
                if (button.id >= CHOICE) {
                    var choice = BuiltinModelCatalog.choices()
                        .get(button.id - CHOICE);
                    lines.add(names.name(choice));
                    for (String line : choice.getDescription()) lines.add(names.text(line));
                    lines.add(text("gui.touhou_little_maid.skin.tooltips.show_details"));
                    if (!choice.getPreferredSoundPack()
                        .trim()
                        .isEmpty())
                        lines.add(
                            "§6" + net.minecraft.util.StatCollector.translateToLocalFormatted(
                                "gui.touhou_little_maid.skin.tooltips.maid_use_sound_pack_id",
                                choice.getPreferredSoundPack()));
                    if (mc.gameSettings.advancedItemTooltips) lines.add("\u00a78" + choice.getId());
                    for (var pack : packs)
                        if (pack.id.equals(choice.getPackId())) lines.add("\u00a79" + names.text(pack.name));
                } else if (button.id >= TAB && button.id < TAB + 7)
                    lines.add(names.text(packs.get(guiNumber.tabToPackIndex(button.id - TAB, getPageIndex())).name));
                else if (button.id == CLOSE) lines.add(text("gui.touhou_little_maid.skin.button.close"));

                if (!lines.isEmpty()) drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
                return;
            }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        for (var widget : buttonList) if (widget instanceof MaidCacheCheckbox checkbox) checkbox.clearFocus();
        if (searchMode) searchBox.mouseClicked(x, y, button);
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.thePlayer.closeScreen();
            return;
        }
        if (searchMode && searchBox.textboxKeyTyped(character, key)) {
            String value = searchBox.getText();
            if (!searchText.equals(value)) search(value);
        } else super.keyTyped(character, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) changeRow(wheel > 0 ? -1 : 1);
    }

    @Override
    public void onGuiClosed() {
        if (mc.thePlayer != null) container.onContainerClosed(mc.thePlayer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal(key);
    }
}
