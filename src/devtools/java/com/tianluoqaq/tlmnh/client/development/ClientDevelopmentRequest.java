package com.tianluoqaq.tlmnh.client.development;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 本地测试控制协议，只接受列出的操作和受限参数 */
public final class ClientDevelopmentRequest {

    private static final Pattern SCREENSHOT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\\.png");
    public final Command command;
    public final String screenshotName;
    public final float yaw, pitch;
    public final int port, warmupTicks;
    public final String model, fault, action, view;

    private ClientDevelopmentRequest(Command command, String screenshotName, float yaw, float pitch, int port,
        String model, String fault, String action, String view, int warmupTicks) {
        this.command = command;
        this.screenshotName = screenshotName;
        this.yaw = yaw;
        this.pitch = pitch;
        this.port = port;
        this.model = model;
        this.fault = fault;
        this.action = action;
        this.view = view;
        this.warmupTicks = warmupTicks;
    }

    public static ClientDevelopmentRequest parse(JsonObject json) {
        if (json == null || !json.has("command")
            || !json.get("command")
                .isJsonPrimitive()
            || !json.getAsJsonPrimitive("command")
                .isString())
            throw new IllegalArgumentException("Missing command");
        Command command = Command.valueOf(
            json.get("command")
                .getAsString()
                .toUpperCase(Locale.ROOT));
        String name = null;
        float yaw = 0, pitch = 0;
        int port = 0;
        int warmupTicks = 0;
        String model = null, fault = "none";
        String action = null, view = null;
        if (command == Command.SCREENSHOT || command == Command.MODELS_UI_CAPTURE
            || command == Command.CABINET_UI_CAPTURE) {
            if (!json.has("name") || !json.get("name")
                .isJsonPrimitive()
                || !json.getAsJsonPrimitive("name")
                    .isString())
                throw new IllegalArgumentException("Missing screenshot name");
            name = json.get("name")
                .getAsString();
            if (name.contains("..") || !SCREENSHOT_NAME.matcher(name)
                .matches()) throw new IllegalArgumentException("Invalid screenshot name");
        }
        if (command == Command.PROTECTION_PROBE) {
            action = text(json, "action");
            if (!Arrays.asList("inspect", "missing_api", "recover")
                .contains(action)) throw new IllegalArgumentException("Unknown protection probe action");
        }
        if (command == Command.WORLD_SOUND_PROBE) {
            action = text(json, "action");
            if (!Arrays.asList("global_0", "global_100", "vanilla", "close_gui", "disconnect")
                .contains(action)) throw new IllegalArgumentException("Unknown world sound probe action");
        }
        if (command == Command.FEEDBACK_WORLD) {
            action = text(json, "action");
            if (!Arrays
                .asList(
                    "open",
                    "status",
                    "select_hand",
                    "look_maid",
                    "interact",
                    "gui_tasks",
                    "gui_attack",
                    "gui_ranged",
                    "view",
                    "restore_view",
                    "close_gui",
                    "disconnect")
                .contains(action)) throw new IllegalArgumentException("Unknown feedback-copy action");
            if (Arrays.asList("open", "select_hand", "view")
                .contains(action)) model = text(json, "model");
        }
        if (command == Command.WORLD_SHADER_PROBE) {
            action = text(json, "action");
            if (!Arrays.asList("status", "off", "selected", "restore")
                .contains(action)) throw new IllegalArgumentException("Unknown world shader probe action");
        }
        if (command == Command.LOOK) {
            yaw = number(json, "yaw", -360, 360);
            pitch = number(json, "pitch", -90, 90);
        }
        if (command == Command.CONNECT_LOCAL) {
            port = (int) number(json, "port", 1024, 65535);
            if (json.get("port")
                .getAsDouble() != port) throw new IllegalArgumentException("Invalid port");
        }
        if (command == Command.RENDER_PROBE) {
            model = text(json, "model");
            boolean reviewedFaultModel = Arrays
                .asList(
                    "geckolib:winefox_hanfu",
                    "authors_and_credits:wine_fox_maid",
                    "touhou_little_maid:hakurei_reimu")
                .contains(model);
            if (!reviewedFaultModel && !BuiltinModelCatalog.contains(model))
                throw new IllegalArgumentException("Unknown builtin render probe model");
            if (json.has("fault")) fault = text(json, "fault");
            if (!Arrays.asList("none", "uv_offset", "wrong_texture", "drop_geometry")
                .contains(fault)) throw new IllegalArgumentException("Unknown render probe fault");
            if (!fault.equals("none") && !reviewedFaultModel)
                throw new IllegalArgumentException("Unreviewed render fault model");
        }
        if (command == Command.HEAD_BINDING_PROBE) {
            model = text(json, "model");
            if (!Arrays.asList("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")
                .contains(model)) throw new IllegalArgumentException("Unreviewed head binding model");
            yaw = json.has("headYaw") ? number(json, "headYaw", -85, 85) : 30;
            pitch = json.has("headPitch") ? number(json, "headPitch", -90, 90) : 15;
            fault = json.has("fault") ? text(json, "fault") : "native";
            if (!Arrays.asList("native", "legacy_sign")
                .contains(fault)) throw new IllegalArgumentException("Unknown head binding fault");
            if (fault.equals("legacy_sign") && !model.equals("geckolib:winefox_hanfu"))
                throw new IllegalArgumentException("Legacy head sign fault requires Gecko");
        }
        if (command == Command.GPU_MATRIX_PROBE) {
            model = text(json, "model");
            if (!Arrays.asList("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")
                .contains(model)) throw new IllegalArgumentException("Unreviewed GPU matrix model");
            if (json.has("fault"))
                throw new IllegalArgumentException("GPU matrix probe uses a fixed five-draw sequence");
        }
        if (command == Command.HELD_ITEM_GPU_PROBE) {
            model = text(json, "model");
            action = text(json, "action");
            view = json.has("view") ? text(json, "view") : "positive_z";
            if (json.has("warmupTicks")) {
                warmupTicks = (int) number(json, "warmupTicks", 0, 60);
                if (json.get("warmupTicks")
                    .getAsDouble() != warmupTicks) throw new IllegalArgumentException("Invalid integer warmupTicks");
            }
            if (!Arrays.asList("touhou_little_maid:hakurei_reimu", "geckolib:winefox")
                .contains(model) || !heldGpuActions().contains(action)
                || !heldGpuViews().contains(view)
                || json.has("fault")) throw new IllegalArgumentException("Unreviewed held GPU fixture");
        }
        if (command == Command.CARE_INTERACT) {
            action = text(json, "action");
            if (!Arrays
                .asList(
                    "interact",
                    "open",
                    "grave",
                    "shrine",
                    "sneak_on",
                    "sneak_off",
                    "status",
                    "native_click",
                    "native_sneak_click")
                .contains(action)) throw new IllegalArgumentException("Unknown native care action");
            if (action.equals("grave")) {
                model = text(json, "model");
                int id = Integer.parseInt(model);
                if (id <= 0 || !Integer.toString(id)
                    .equals(model)) throw new IllegalArgumentException("Actual native tombstone entity ID required");
            }
            if (action.equals("interact") || action.equals("open")
                || action.equals("status")
                || action.equals("native_click")
                || action.equals("native_sneak_click")) {
                model = text(json, "model");
                if (!UUID.fromString(model)
                    .toString()
                    .equals(model)) throw new IllegalArgumentException("Canonical maid UUID required");
            }
        }
        if (command == Command.MODELS_UI) {
            action = text(json, "action");
            if (!Arrays
                .asList(
                    "status",
                    "container_status",
                    "container_click",
                    "container_button",
                    "container_replay_button",
                    "naming_text",
                    "naming_visibility",
                    "naming_save",
                    "naming_repeat",
                    "naming_cancel",
                    "naming_escape",
                    "naming_enter",
                    "open",
                    "select",
                    "back",
                    "next",
                    "previous",
                    "winefox",
                    "all",
                    "home",
                    "home_toggle",
                    "home_work",
                    "home_idle",
                    "home_rest",
                    "home_reset",
                    "tasks",
                    "idle",
                    "attack",
                    "ranged",
                    "pointer",
                    "schedule",
                    "pickup",
                    "ride_toggle",
                    "cache",
                    "sound",
                    "sound_apply",
                    "config",
                    "global",
                    "global_sound_zero",
                    "global_sound_mid",
                    "global_sound_full",
                    "global_bubbles",
                    "global_cache",
                    "global_save",
                    "global_cancel",
                    "main_tab",
                    "config_down",
                    "config_up",
                    "config_label",
                    "config_type_previous",
                    "config_type_next",
                    "config_door",
                    "config_gate",
                    "config_climb",
                    "config_back_item",
                    "config_bubble",
                    "sound_play",
                    "details",
                    "details_toggle",
                    "details_shield_fault",
                    "details_shield_blocking",
                    "details_equipment",
                    "details_action",
                    "details_view",
                    "pack",
                    "search",
                    "task_config",
                    "target_add",
                    "target_remove",
                    "target_previous",
                    "target_next",
                    "target_page_next",
                    "target_page_previous",
                    "target_close")
                .contains(action)) throw new IllegalArgumentException("Unknown model UI probe action");
            if (action.equals("select") || action.equals("pack")
                || action.equals("search")
                || action.equals("details")
                || action.equals("details_toggle")
                || action.equals("details_shield_blocking")
                || action.equals("details_equipment")
                || action.equals("details_action")
                || action.equals("details_view")
                || action.equals("sound_play")
                || action.equals("naming_text")
                || action.equals("container_click")
                || action.equals("container_button")
                || action.equals("target_add")
                || action.equals("target_remove")
                || action.equals("target_previous")
                || action.equals("target_next")) model = text(json, "model");
            if (action.equals("naming_text")) {
                if (model.length() > 32 || model.chars()
                    .anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Naming text must fit the native 32-character field");
            }
            if (action.equals("details_equipment") && !heldGpuActions().contains(model)
                && !Arrays.asList("clear_main", "clear_offhand")
                    .contains(model))
                throw new IllegalArgumentException("Reviewed native preview equipment required");
            if (action.equals("details_action")
                && !Arrays.asList("idle", "look_left", "look_right", "attack", "aim", "eat", "sleep", "wake", "hurt")
                    .contains(model))
                throw new IllegalArgumentException("Reviewed preview action required");
        }
        return new ClientDevelopmentRequest(command, name, yaw, pitch, port, model, fault, action, view, warmupTicks);
    }

    private static String text(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name)
            .isJsonPrimitive()
            || !json.getAsJsonPrimitive(name)
                .isString())
            throw new IllegalArgumentException("Missing string " + name);
        return json.get(name)
            .getAsString();
    }

    public static List<String> heldGpuActions() {
        return Arrays.asList(
            "shield_wood",
            "shield_hide",
            "shield_iron",
            "shield_diamond",
            "shield_gold",
            "shield_round",
            "shield_heater",
            "main_icon",
            "main_bow",
            "main_block",
            "main_tool");
    }

    public static List<String> heldGpuViews() {
        return Arrays.asList("positive_z", "negative_z", "positive_x", "negative_x");
    }

    private static float number(JsonObject json, String name, double minimum, double maximum) {
        if (!json.has(name) || !json.get(name)
            .isJsonPrimitive()
            || !json.getAsJsonPrimitive(name)
                .isNumber())
            throw new IllegalArgumentException("Missing numeric " + name);
        double value = json.get(name)
            .getAsDouble();
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException("Invalid " + name);
        return (float) value;
    }

    public enum Command {
        STATUS,
        CARE_INTERACT,
        WORLD_SOUND_PROBE,
        WORLD_SHADER_PROBE,
        FEEDBACK_WORLD,
        PROTECTION_PROBE,
        CONNECT_LOCAL,
        LAB_VIEW,
        LOOK,
        SCREENSHOT,
        RENDER_PROBE,
        HEAD_BINDING_PROBE,
        GPU_MATRIX_PROBE,
        HELD_ITEM_GPU_PROBE,
        GECKO_LIBRARY_PROBE,
        MODELS_UI,
        MODELS_UI_CAPTURE,
        CABINET_UI_CAPTURE,
        RELOAD,
        SHUTDOWN
    }
}
