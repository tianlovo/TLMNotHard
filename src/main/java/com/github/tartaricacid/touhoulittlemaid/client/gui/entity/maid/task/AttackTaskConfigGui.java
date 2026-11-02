/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/maid/task/AttackTaskConfigGui.java, MIT
 * Adaptation: native widgets, registered legacy living types, bounded server-owned editor session
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MonsterListButton;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.tianluoqaq.tlmnh.client.gui.MaidAtlasButton;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.network.MaidAttackListPayload;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class AttackTaskConfigGui extends MaidTaskConfigGui {

    private static final ResourceLocation BG = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/attack_task_config.png");
    private final List<ResourceLocation> keys = new ArrayList<>();
    private final Map<ResourceLocation, String> known = MaidCombatEntities.registeredLivingTypes();
    private GuiTextField input;
    private int page;
    private boolean observedReady;
    private String feedback = "";

    public AttackTaskConfigGui(TaskConfigContainer container) {
        super(container);
    }

    private boolean canEdit() {
        return maidContainer.ready() && maidContainer.editable()
            && mc.thePlayer != null
            && mc.thePlayer.openContainer == inventorySlots
            && inventorySlots.canInteractWith(mc.thePlayer)
            && mc.thePlayer.inventory.getItemStack() == null;
    }

    private void sortKeys() {
        keys.clear();
        keys.addAll(getAttackGroups().keySet());
        keys.sort(
            Comparator.<ResourceLocation>comparingInt(
                id -> 2 - getAttackGroups().get(id)
                    .ordinal())
                .thenComparing(ResourceLocation::toString));
        page = Math.max(0, Math.min(page, Math.max(0, (keys.size() - 1) / 7)));
    }

    @Override
    protected void initAdditionWidgets() {
        String old = input == null ? "" : input.getText();
        sortKeys();
        int x = guiLeft + 87, y = guiTop + 36;
        input = new GuiTextField(fontRendererObj, x, y, 117, 16);
        input.setMaxStringLength(256);
        input.setText(old);
        buttonList.add(
            new GuiButton(
                500,
                x + 119,
                y - 1,
                44,
                18,
                StatCollector.translateToLocal("gui.touhou_little_maid.monster_type.add")));
        buttonList.add(new MaidAtlasButton(501, x + 121, y + 20, 5, 9, BG, 0, 176, 9, 0, ""));
        buttonList.add(new MaidAtlasButton(502, x + 156, y + 20, 5, 9, BG, 5, 176, 9, 0, ""));
        for (int row = 0; row < 7 && page * 7 + row < keys.size(); row++) {
            var id = keys.get(page * 7 + row);
            var nativeName = known.get(id);
            String name = nativeName == null ? id.toString()
                : StatCollector.translateToLocal("entity." + nativeName + ".name");
            buttonList.add(new MonsterListButton(510 + row, name, x - 1, y + 31 + 13 * row, id, this));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!observedReady && maidContainer.ready()) {
            observedReady = true;
            initGui();
        }
        input.updateCursorCounter();
        input.setEnabled(canEdit());
        for (Object raw : buttonList)
            if (raw instanceof GuiButton button && button.id >= 500) button.enabled = canEdit();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < 500) {
            super.actionPerformed(button);
            return;
        }
        if (!canEdit()) return;
        if (button instanceof MonsterListButton row) {
            row.applyClick();
            return;
        }
        if (button.id == 500) {
            String value = input.getText();
            if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !known.containsKey(new ResourceLocation(value))) {
                feedback = "gui.tlmnh.task_config.unknown_type";
                return;
            }
            var id = new ResourceLocation(value);
            var next = new java.util.HashMap<>(getAttackGroups());
            next.put(id, MonsterType.NEUTRAL);
            if (!MaidAttackListPayload.fits(next)) {
                feedback = "gui.tlmnh.task_config.unavailable";
                return;
            }
            getAttackGroups().put(id, MonsterType.NEUTRAL);
            maidContainer.markEdited();
            feedback = "";
        } else page += button.id == 501 ? -1 : 1;
        initGui();
    }

    public Map<ResourceLocation, MonsterType> getAttackGroups() {
        return maidContainer.editedGroups();
    }

    public void edit(ResourceLocation id, int part) {
        if (!canEdit()) return;
        if (part == 1) getAttackGroups().remove(id);
        else if (part == 2) getAttackGroups().computeIfPresent(id, (key, type) -> type.getPrevious());
        else if (part == 3) getAttackGroups().computeIfPresent(id, (key, type) -> type.getNext());
        else return;
        maidContainer.markEdited();
        if (part == 1) initGui();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        input.mouseClicked(x, y, button);
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key != 1 && input.textboxKeyTyped(character, key)) return;
        if (key != 1 && input.isFocused()) return;
        super.keyTyped(character, key);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        super.drawGuiContainerBackgroundLayer(partialTick, mouseX, mouseY);
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(BG);
        drawTexturedModalRect(guiLeft + 80, guiTop + 28, 0, 0, xSize, 137);
        input.drawTextBox();
    }

    @Override
    protected void renderAddition(float partialTick, int mouseX, int mouseY) {
        drawCenteredString(
            fontRendererObj,
            (page + 1) + "/" + (Math.max(0, (keys.size() - 1) / 7) + 1),
            guiLeft + 228,
            guiTop + 57,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.touhou_little_maid.monster_type.title"),
            guiLeft + 147,
            guiTop + 57,
            0xFFFFFF);
        String message = !maidContainer.ready() ? "gui.tlmnh.task_config.loading"
            : !maidContainer.editable() ? "gui.tlmnh.task_config.unavailable" : feedback;
        if (!message.isEmpty()) fontRendererObj
            .drawSplitString(StatCollector.translateToLocal(message), guiLeft + 88, guiTop + 69, 160, 0x555555);
    }
}
