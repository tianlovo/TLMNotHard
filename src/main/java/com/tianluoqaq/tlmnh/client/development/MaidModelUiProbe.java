package com.tianluoqaq.tlmnh.client.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.detail.MaidModelDetailsGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.GuiMaid;
import com.tianluoqaq.tlmnh.client.gui.GuiMaidModels;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 复用真实GUI按钮业务并观察客户端副本，服务端确认须另查窗口和实体状态 */
@SideOnly(Side.CLIENT)
public final class MaidModelUiProbe {

    private MaidModelUiProbe() {}

    public static JsonObject observe() {
        requireWindow();
        return snapshot();
    }

    public static JsonObject perform(String action, String model) {
        requireWindow();
        var minecraft = Minecraft.getMinecraft();
        boolean requested = false, network = false;
        if (!action.equals("status")) {
            if (minecraft.currentScreen instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui sounds) {
                if (action.equals("pack")) {
                    var packs = com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog.packs();
                    for (int i = 0; i < packs.size(); i++)
                        if (packs.get(i).id.equals(model)) requested = sounds.pressButton(1000 + i);
                } else if (action.equals("sound_apply")) {
                    requested = sounds.pressButton(3);
                    network = requested;
                } else if (action.equals("next")) requested = sounds.pressButton(7);
                else if (action.equals("previous")) requested = sounds.pressButton(6);
                else if (action.equals("sound_play")) {
                    var state = sounds.observation();
                    var pack = com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog.get(
                        state.get("selected")
                            .getAsString());
                    var ids = new java.util.ArrayList<>(pack.events.keySet());
                    int index = ids.indexOf(model);
                    if (index >= 0) requested = sounds.pressButton(2000 + index);
                } else throw new IllegalArgumentException("Action does not apply to the sound screen");
            } else if (minecraft.currentScreen instanceof MaidModelDetailsGui details) {
                switch (action) {
                    case "back" -> requested = details.pressButton(2);
                    case "details_toggle" -> {
                        var keys = java.util.Arrays.asList(
                            "beg",
                            "walk",
                            "sit",
                            "ride",
                            "helmet",
                            "chest_plate",
                            "leggings",
                            "boots",
                            "main_hand",
                            "off_hand");
                        int index = keys.indexOf(model);
                        if (index < 0 && !"floor".equals(model))
                            throw new IllegalArgumentException("Unknown details toggle");
                        requested = details.pressButton(index < 0 ? 1 : 10 + index);
                    }
                    case "details_view" -> requested = switch (model) {
                            case "rotate" -> details.dragView(200, 100, 0, 40, 10);
                            case "pan" -> details.dragView(200, 100, 1, 15, -10);
                            case "zoom_in" -> details.scrollView(200, 100, 1);
                            case "zoom_min" -> details.scrollView(200, 100, -100);
                            case "zoom_max" -> details.scrollView(200, 100, 1000);
                            case "outside" -> details.dragView(132, 100, 0, 100, 100) || details.scrollView(200, 15, 1);
                            default -> throw new IllegalArgumentException("Unknown details view fixture");
                        };
                    default -> throw new IllegalArgumentException("Action does not apply to the details screen");
                }
            } else if (minecraft.currentScreen instanceof MaidModelGui models) {
                switch (action) {
                    case "select", "details" -> {
                        for (int index : models.getVisibleModelIndices()) if (BuiltinModelCatalog.choices()
                            .get(index)
                            .getId()
                            .equals(model)) {
                                requested = action.equals("details") ? models.openDetails(index)
                                    : models.selectVisibleModel(index);
                                network = requested && !action.equals("details");
                                break;
                            }
                    }
                    case "back" -> {
                        requested = models.returnToMaid();
                        network = requested;
                    }
                    case "next" -> requested = models.changeRow(1);
                    case "previous" -> requested = models.changeRow(-1);
                    case "pack" -> requested = models.selectPack(model);
                    case "search" -> {
                        models.search(model);
                        requested = true;
                    }
                    case "cache" -> {
                        models.toggleIconCache();
                        requested = true;
                    }
                    case "pointer" -> {
                        org.lwjgl.input.Mouse.setCursorPosition(0, 0);
                        requested = true;
                    }
                    default -> throw new IllegalArgumentException("Action does not apply to the original model screen");
                }
            } else if (minecraft.currentScreen instanceof AbstractMaidContainerGui<?>original) {
                if (action.equals("pointer")) {
                    // RC2 lwjgl3ify 的 SDL Warp 接口采用左上原点，按实际帧坐标复核
                    org.lwjgl.input.Mouse.setCursorPosition(0, 0);
                    requested = true;
                } else {
                    requested = original.requestAction(action);
                    network = requested && !action.equals("tasks");
                }
            } else if (minecraft.currentScreen instanceof GuiMaid main) {
                if (action.equals("open")) {
                    requested = main.openModelSelector();
                    network = requested;
                } else if (action.equals("home")) requested = main.openHomeSettings();
                else {
                    int button = switch (action) {
                        case "home_toggle" -> ContainerMaid.ACTION_HOME;
                        case "home_work" -> ContainerMaid.ACTION_HOME_WORK;
                        case "home_idle" -> ContainerMaid.ACTION_HOME_IDLE;
                        case "home_rest" -> ContainerMaid.ACTION_HOME_REST;
                        case "home_reset" -> ContainerMaid.ACTION_HOME_RESET;
                        default -> throw new IllegalArgumentException("Action does not apply to the main maid screen");
                    };
                    requested = main.requestHomeAction(button);
                    network = requested;
                }
            } else if (minecraft.currentScreen instanceof GuiMaidModels selector) {
                switch (action) {
                    case "select":
                        for (int index : selector.getVisibleModelIndices()) {
                            if (BuiltinModelCatalog.choices()
                                .get(index)
                                .getId()
                                .equals(model)) {
                                requested = selector.selectVisibleModel(index);
                                network = requested;
                                break;
                            }
                        }
                        break;
                    case "back":
                        requested = selector.returnToMaid();
                        network = requested;
                        break;
                    case "next":
                        requested = selector.changePage(1);
                        break;
                    case "previous":
                        requested = selector.changePage(-1);
                        break;
                    case "winefox":
                        selector.setWinefoxOnly(true);
                        requested = true;
                        break;
                    case "all":
                        selector.setWinefoxOnly(false);
                        requested = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Action does not apply to this model screen");
                }
            } else throw new IllegalStateException("Open the maid model selector for this action");
        }
        var response = snapshot();
        response.addProperty("action", action);
        response.addProperty("actionRequested", requested);
        response.addProperty("networkActionRequested", network);
        response.addProperty("serverAcknowledged", false);
        return response;
    }

    private static JsonObject snapshot() {
        var minecraft = Minecraft.getMinecraft();
        var container = minecraft.thePlayer.openContainer;
        EntityMaid maid = container instanceof ContainerMaid main ? main.getMaid()
            : container instanceof com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds sounds ? sounds.getMaid()
                : ((ContainerMaidModels) container).getMaid();
        var response = new JsonObject();
        response.addProperty(
            "currentScreen",
            minecraft.currentScreen.getClass()
                .getSimpleName());
        response.addProperty("windowId", container.windowId);
        response.addProperty("playerWindowId", container.windowId);
        response.addProperty(
            "maidUuid",
            maid.getUniqueID()
                .toString());
        response.addProperty("maidModel", maid.getModelId());
        response.addProperty("maidSoundPack", maid.getSoundPackId());
        if (minecraft.currentScreen instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui sounds)
            response.add("sounds", sounds.observation());
        response.addProperty("clientReplicaOnly", true);
        response.add("iconCache", CacheIconManager.INSTANCE.observation());
        if (minecraft.currentScreen instanceof MaidModelDetailsGui details) {
            response.add("details", details.observation());
            response.addProperty("scaledWidth", details.width);
            response.addProperty("scaledHeight", details.height);
        }
        if (minecraft.currentScreen instanceof MaidModelGui models) {
            response.addProperty("pack", models.getPackId());
            response.addProperty("row", models.getRow());
            response.addProperty("searchMode", models.isSearchMode());
            response.addProperty("searchText", models.getSearchText());
            response.addProperty("scaledWidth", models.width);
            response.addProperty("scaledHeight", models.height);
            response.addProperty("lastRenderMouseX", models.getLastMouseX());
            response.addProperty("lastRenderMouseY", models.getLastMouseY());
            var visible = new JsonArray();
            for (int index : models.getVisibleModelIndices()) {
                var choice = BuiltinModelCatalog.choices()
                    .get(index);
                var entry = new JsonObject();
                entry.addProperty("index", index);
                entry.addProperty("model", choice.getId());
                entry.addProperty("winefox", choice.isWinefox());
                visible.add(entry);
            }
            response.add("visibleModels", visible);
        }
        if (minecraft.currentScreen instanceof AbstractMaidContainerGui<?>original) {
            response.addProperty("taskListOpen", original.isTaskListOpen());
            response.addProperty("scaledWidth", original.width);
            response.addProperty("scaledHeight", original.height);
            response.addProperty("slotCount", original.inventorySlots.inventorySlots.size());
            response.addProperty("lastRenderMouseX", original.getLastMouseX());
            response.addProperty("lastRenderMouseY", original.getLastMouseY());
            response.addProperty(
                "task",
                maid.getTask()
                    .getId());
            response.addProperty(
                "schedule",
                maid.getSchedule()
                    .name());
            response.addProperty(
                "homeEnabled",
                maid.getHomeArea()
                    .enabled());
            response.addProperty("pickupItems", maid.canPickupItems());
            response.addProperty("pickupExperience", maid.canPickupExperience());
        }
        if (minecraft.currentScreen instanceof GuiMaid main) {
            response.addProperty("homePage", main.isHomeSettingsPage());
            response.addProperty(
                "homeEnabled",
                maid.getHomeArea()
                    .enabled());
            response.addProperty(
                "homeSettings",
                maid.getHomeArea()
                    .settings()
                    .encode());
            response.addProperty("scaledWidth", main.width);
            response.addProperty("scaledHeight", main.height);
        }
        if (minecraft.currentScreen instanceof GuiMaidModels selector) {
            var visible = new JsonArray();
            for (int index : selector.getVisibleModelIndices()) {
                var choice = BuiltinModelCatalog.choices()
                    .get(index);
                var entry = new JsonObject();
                entry.addProperty("index", index);
                entry.addProperty("model", choice.getId());
                entry.addProperty("winefox", choice.isWinefox());
                entry.addProperty(
                    "selected",
                    choice.getId()
                        .equals(maid.getModelId()));
                visible.add(entry);
            }
            response.add("visibleModels", visible);
        }
        return response;
    }

    public static void requireCaptureScreen() {
        requireWindow();
        var screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui)
            && !(screen instanceof MaidModelDetailsGui)
            && !(screen instanceof CacheScreen)
            && !(screen instanceof MaidModelGui)
            && !(screen instanceof AbstractMaidContainerGui<?>)
            && !(screen instanceof GuiMaidModels)
            && !(screen instanceof GuiMaid main && main.isHomeSettingsPage()))
            throw new IllegalStateException("Maid GUI capture requires the actual selector or home settings page");
    }

    private static void requireWindow() {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Model UI probe disabled");
        var minecraft = Minecraft.getMinecraft();
        var player = minecraft.thePlayer;
        if (!AnimationLab.isLab(minecraft.theWorld) || player == null
            || !player.capabilities.disableDamage
            || !player.capabilities.isFlying
            || !player.noClip) throw new IllegalStateException("Safe lab observer required");
        var actual = minecraft.currentScreen instanceof GuiContainer screen ? screen.inventorySlots
            : minecraft.currentScreen instanceof MaidModelGui models ? models.getContainer()
                : minecraft.currentScreen instanceof CacheScreen cache ? cache.getContainer()
                    : minecraft.currentScreen instanceof MaidModelDetailsGui details ? details.getContainer() : null;
        if (actual == null || actual != player.openContainer
            || !actual.canInteractWith(player)
            || !(minecraft.currentScreen instanceof AbstractMaidContainerGui<?>
                && player.openContainer instanceof com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer
                || (minecraft.currentScreen instanceof MaidModelGui || minecraft.currentScreen instanceof CacheScreen
                    || minecraft.currentScreen instanceof MaidModelDetailsGui)
                    && player.openContainer instanceof ContainerMaidModels
                || minecraft.currentScreen instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.sound.MaidSoundPackGui
                    && player.openContainer instanceof com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds
                || minecraft.currentScreen instanceof GuiMaid && player.openContainer instanceof ContainerMaid
                || minecraft.currentScreen instanceof GuiMaidModels
                    && player.openContainer instanceof ContainerMaidModels))
            throw new IllegalStateException("Actual owned maid window required");
    }
}
