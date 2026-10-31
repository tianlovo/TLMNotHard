package com.tianluoqaq.tlmnh.client.development;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/** 本地测试控制协议，只接受列出的操作和受限参数 */
public final class ClientDevelopmentRequest {

    private static final Pattern SCREENSHOT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\\.png");
    public final Command command;
    public final String screenshotName;
    public final float yaw, pitch;
    public final int port;
    public final String model, fault, action;

    private ClientDevelopmentRequest(Command command, String screenshotName, float yaw, float pitch, int port,
        String model, String fault, String action) {
        this.command = command;
        this.screenshotName = screenshotName;
        this.yaw = yaw;
        this.pitch = pitch;
        this.port = port;
        this.model = model;
        this.fault = fault;
        this.action = action;
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
        String model = null, fault = "none";
        String action = null;
        if (command == Command.SCREENSHOT || command == Command.MODELS_UI_CAPTURE) {
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
            if (!Arrays.asList("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")
                .contains(model)) throw new IllegalArgumentException("Unreviewed render probe model");
            if (json.has("fault")) fault = text(json, "fault");
            if (!Arrays.asList("none", "uv_offset", "wrong_texture", "drop_geometry")
                .contains(fault)) throw new IllegalArgumentException("Unknown render probe fault");
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
        if (command == Command.MODELS_UI) {
            action = text(json, "action");
            if (!Arrays.asList("status", "open", "select", "back", "next", "previous", "winefox", "all")
                .contains(action)) throw new IllegalArgumentException("Unknown model UI probe action");
            if (action.equals("select")) model = text(json, "model");
        }
        return new ClientDevelopmentRequest(command, name, yaw, pitch, port, model, fault, action);
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
        CONNECT_LOCAL,
        LAB_VIEW,
        LOOK,
        SCREENSHOT,
        RENDER_PROBE,
        HEAD_BINDING_PROBE,
        GPU_MATRIX_PROBE,
        GECKO_LIBRARY_PROBE,
        MODELS_UI,
        MODELS_UI_CAPTURE,
        RELOAD,
        SHUTDOWN
    }
}
