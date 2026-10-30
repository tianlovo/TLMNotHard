package com.tianluoqaq.tlmnh.client.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.development.ClientDevelopmentRequest.Command;

public class ClientDevelopmentRequestTest {

    @Test
    public void acceptsOnlyTheListedCommands() {
        for (var command : new Command[] { Command.STATUS, Command.LAB_VIEW, Command.RELOAD, Command.SHUTDOWN })
            assertEquals(
                command,
                parse(
                    "{\"command\":\"" + command.name()
                        .toLowerCase(java.util.Locale.ROOT) + "\"}").command);
        for (String json : new String[] { "{}", "{\"command\":null}", "{\"command\":1}", "{\"command\":{}}",
            "{\"command\":\"chat\"}", "{\"command\":\"exec\"}" })
            assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(null));
    }

    @Test
    public void localConnectionsRequireAnIntegerUnprivilegedPort() {
        var request = new JsonObject();
        request.addProperty("command", "connect_local");
        for (int port : new int[] { 1024, 25585, 65535 }) {
            request.addProperty("port", port);
            assertEquals(port, ClientDevelopmentRequest.parse(request).port);
        }
        for (double invalid : new double[] { 0, 1023, 65536, 25585.5, Double.NaN, Double.POSITIVE_INFINITY }) {
            request.addProperty("port", invalid);
            assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"connect_local\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"connect_local\",\"port\":\"25585\"}"));
    }

    @Test
    public void screenshotNamesCannotEscapeTheScreenshotDirectory() {
        var request = new JsonObject();
        request.addProperty("command", "screenshot");
        for (String name : new String[] { "winefox-01.png", "model_2.frame-17.png" }) {
            request.addProperty("name", name);
            assertEquals(name, ClientDevelopmentRequest.parse(request).screenshotName);
        }
        for (String name : new String[] { "../escape.png", "..\\escape.png", "/escape.png", "directory/capture.png",
            "directory\\capture.png", "capture.png:stream", "capture..png", ".hidden.png", "capture.jpg", "capture.PNG",
            "", "capture\n.png" }) {
            request.addProperty("name", name);
            assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        }
        request.addProperty("name", new String(new char[103]).replace('\0', 'a') + ".png");
        assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"screenshot\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"screenshot\",\"name\":2}"));
    }

    @Test
    public void cameraAnglesRequireFiniteNumbersWithinBounds() {
        var request = new JsonObject();
        request.addProperty("command", "look");
        request.addProperty("yaw", -360);
        request.addProperty("pitch", 90);
        var parsed = ClientDevelopmentRequest.parse(request);
        assertEquals(-360, parsed.yaw, 0);
        assertEquals(90, parsed.pitch, 0);
        for (double invalid : new double[] { -360.01, 360.01, Double.NaN, Double.POSITIVE_INFINITY }) {
            request.addProperty("yaw", invalid);
            assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        }
        request.addProperty("yaw", 22.5);
        for (double invalid : new double[] { -90.01, 90.01, Double.NaN, Double.NEGATIVE_INFINITY }) {
            request.addProperty("pitch", invalid);
            assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"look\",\"yaw\":\"0\",\"pitch\":0}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"look\",\"yaw\":0}"));
    }

    private static ClientDevelopmentRequest parse(String text) {
        return ClientDevelopmentRequest.parse(
            new JsonParser().parse(text)
                .getAsJsonObject());
    }
}
