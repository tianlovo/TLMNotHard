package com.tianluoqaq.tlmnh.client.development;

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

    private ClientDevelopmentRequest(Command command, String screenshotName, float yaw, float pitch, int port) {
        this.command = command;
        this.screenshotName = screenshotName;
        this.yaw = yaw;
        this.pitch = pitch;
        this.port = port;
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
        if (command == Command.SCREENSHOT) {
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
        return new ClientDevelopmentRequest(command, name, yaw, pitch, port);
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
        RELOAD,
        SHUTDOWN
    }
}
