package com.tianluoqaq.tlmnh.client.development.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.work.BreedingConfigGuiIds;
import com.tianluoqaq.tlmnh.client.gui.work.BreedingConfigGuiLayout;
import com.tianluoqaq.tlmnh.client.gui.work.BreedingTaskConfigGui;
import com.tianluoqaq.tlmnh.common.work.container.BreedingTaskConfigContainer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发动作只提交真实编辑器控件输入，权威结果另由服务端回执确认 */
@SideOnly(Side.CLIENT)
public final class BreedingConfigUiProbe {

    private BreedingConfigUiProbe() {}

    public static boolean perform(BreedingTaskConfigGui gui, String action, String value) {
        if (action.equals("breeding_status")) return false;
        if (action.equals("breeding_search")) return gui.setSearchText(value);
        int id = switch (action) {
            case "breeding_mode" -> BreedingConfigGuiIds.MODE;
            case "breeding_view" -> BreedingConfigGuiIds.VIEW;
            case "breeding_save" -> BreedingConfigGuiIds.SAVE;
            case "breeding_logic" -> BreedingConfigGuiIds.LOGIC;
            case "breeding_max31" -> BreedingConfigGuiIds.MAX31;
            case "breeding_previous" -> BreedingConfigGuiIds.PREVIOUS;
            case "breeding_next" -> BreedingConfigGuiIds.NEXT;
            default -> BreedingConfigGuiIds.UNKNOWN_BUTTON;
        };
        try {
            if (action.equals("breeding_select")) {
                int row = Integer.parseInt(value);
                if (row < 0 || row >= gui.visibleCandidates()
                    .size()) return false;
                id = BreedingConfigGuiIds.FIRST_CANDIDATE + row;
            } else if (id == BreedingConfigGuiIds.UNKNOWN_BUTTON) {
                int row = switch (action) {
                    case "breeding_gain" -> BreedingConfigGuiLayout.GAIN_ROW;
                    case "breeding_growth" -> BreedingConfigGuiLayout.GROWTH_ROW;
                    case "breeding_resistance" -> BreedingConfigGuiLayout.RESISTANCE_ROW;
                    default -> BreedingConfigGuiIds.UNKNOWN_BUTTON;
                };
                if (row < 0) return false;
                int delta = Integer.parseInt(value);
                for (int step = 0; step < BreedingConfigGuiLayout.STEP_COUNT; step++) {
                    if (BreedingConfigGuiLayout.STEPS[step] == delta)
                        id = BreedingConfigGuiIds.FIRST_STAT + row * BreedingConfigGuiLayout.STEP_COUNT + step;
                }
            }
        } catch (NumberFormatException invalid) {
            return false;
        }
        return gui.pressButton(id);
    }

    public static JsonObject observe(BreedingTaskConfigGui gui, BreedingTaskConfigContainer menu) {
        var result = new JsonObject();
        result.addProperty("ready", menu.ready());
        result.addProperty("editable", menu.editable());
        result.addProperty("dirty", menu.dirty());
        result.addProperty("submitted", gui.hasSubmitted());
        result.addProperty("attributesPage", gui.isAttributesPage());
        var config = menu.editedConfig();
        var state = new JsonObject();
        state.addProperty(
            "mode",
            config.targetMode()
                .name());
        state.addProperty("target", config.targetId());
        state.addProperty("gain", config.targetGain());
        state.addProperty("growth", config.targetGrowth());
        state.addProperty("resistance", config.targetResistance());
        state.addProperty(
            "logic",
            config.statLogic()
                .name());
        state.addProperty("max31", config.max31Mode());
        result.add("config", state);
        var candidates = new JsonArray();
        for (var entry : gui.visibleCandidates()) {
            var candidate = new JsonObject();
            candidate.addProperty("id", entry.id);
            candidate.addProperty("nameKey", entry.unlocalizedName);
            candidates.add(candidate);
        }
        result.add("visibleCandidates", candidates);
        return result;
    }
}
