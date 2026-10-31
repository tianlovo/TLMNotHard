package com.tianluoqaq.tlmnh.client.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.development.ClientDevelopmentRequest.Command;

public class ClientDevelopmentRequestTest {

    @Test
    public void heldGpuPilotAcceptsBothModelsAndAllReviewedProfiles() {
        for (String model : new String[] { "touhou_little_maid:hakurei_reimu", "geckolib:winefox" }) {
            for (String action : new String[] { "shield_wood", "shield_hide", "shield_iron", "shield_diamond",
                "shield_gold", "shield_round", "shield_heater", "main_icon", "main_bow", "main_block", "main_tool" }) {
                var request = new JsonObject();
                request.addProperty("command", "held_item_gpu_probe");
                request.addProperty("model", model);
                request.addProperty("action", action);
                var parsed = ClientDevelopmentRequest.parse(request);
                assertEquals(Command.HELD_ITEM_GPU_PROBE, parsed.command);
                assertEquals(model, parsed.model);
                assertEquals(action, parsed.action);
                assertEquals("positive_z", parsed.view);
                assertEquals(0, parsed.warmupTicks);
            }
        }
    }

    @Test
    public void heldGpuWarmupUsesBoundedIntegerTicks() {
        var request = new JsonObject();
        request.addProperty("command", "held_item_gpu_probe");
        request.addProperty("model", "geckolib:winefox");
        request.addProperty("action", "main_bow");
        for (int ticks : new int[] { 0, 1, 30, 60 }) {
            request.addProperty("warmupTicks", ticks);
            assertEquals(ticks, ClientDevelopmentRequest.parse(request).warmupTicks);
        }
        for (double invalid : new double[] { -1, 61, 1.5, Double.NaN, Double.POSITIVE_INFINITY }) {
            request.addProperty("warmupTicks", invalid);
            assertThrows(IllegalArgumentException.class, () -> ClientDevelopmentRequest.parse(request));
        }
        for (String value : new String[] { "null", "true", "\"30\"", "[]", "{}" }) assertThrows(
            IllegalArgumentException.class,
            () -> parse(
                "{\"command\":\"held_item_gpu_probe\","
                    + "\"model\":\"geckolib:winefox\",\"action\":\"main_bow\",\"warmupTicks\":"
                    + value
                    + "}"));
    }

    @Test
    public void heldGpuViewsAreRestrictedIndependentTransactions() {
        for (String view : new String[] { "positive_z", "negative_z", "positive_x", "negative_x" }) {
            var request = parse(
                "{\"command\":\"held_item_gpu_probe\",\"model\":\"geckolib:winefox\","
                    + "\"action\":\"main_icon\",\"view\":\""
                    + view
                    + "\"}");
            assertEquals(view, request.view);
        }
        String prefix = "{\"command\":\"held_item_gpu_probe\",\"model\":\"geckolib:winefox\","
            + "\"action\":\"main_icon\",\"view\":";
        for (String invalid : new String[] { "null}", "false}", "2}", "\"unknown\"}", "\"POSITIVE_Z\"}", "[]}" })
            assertThrows(IllegalArgumentException.class, () -> parse(prefix + invalid));
    }

    @Test
    public void heldGpuPilotRejectsUnreviewedOrIncompleteInputsAndExternalFaults() {
        String prefix = "{\"command\":\"held_item_gpu_probe\",";
        for (String fields : new String[] { "\"model\":\"unknown\",\"action\":\"main_icon\"}",
            "\"model\":\"geckolib:winefox\",\"action\":\"unknown\"}", "\"model\":\"geckolib:winefox\"}",
            "\"action\":\"main_icon\"}", "\"model\":null,\"action\":\"main_icon\"}",
            "\"model\":\"geckolib:winefox\",\"action\":false}",
            "\"model\":\"geckolib:winefox\",\"action\":\"main_icon\",\"fault\":\"none\"}",
            "\"model\":\"geckolib:winefox\",\"action\":\"main_icon\",\"fault\":null}" })
            assertThrows(IllegalArgumentException.class, () -> parse(prefix + fields));
    }

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

    @Test
    public void renderProbeAcceptsOnlyReviewedModelsAndExplicitFaults() {
        for (String model : new String[] { "geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid" }) {
            for (String fault : new String[] { "none", "uv_offset", "wrong_texture", "drop_geometry" }) {
                var request = parse(
                    "{\"command\":\"render_probe\",\"model\":\"" + model + "\",\"fault\":\"" + fault + "\"}");
                assertEquals(model, request.model);
                assertEquals(fault, request.fault);
            }
        }
        for (String json : new String[] { "{\"command\":\"render_probe\"}",
            "{\"command\":\"render_probe\",\"model\":null}",
            "{\"command\":\"render_probe\",\"model\":\"touhou_little_maid:hakurei_reimu\"}",
            "{\"command\":\"render_probe\",\"model\":\"geckolib:winefox_hanfu\",\"fault\":\"random\"}" })
            assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    @Test
    public void headBindingProbeKeepsBackendFaultsAndAnglesExplicit() {
        var defaults = parse("{\"command\":\"head_binding_probe\",\"model\":\"geckolib:winefox_hanfu\"}");
        assertEquals(Command.HEAD_BINDING_PROBE, defaults.command);
        assertEquals(30, defaults.yaw, 0);
        assertEquals(15, defaults.pitch, 0);
        assertEquals("native", defaults.fault);
        var fault = parse(
            "{\"command\":\"head_binding_probe\",\"model\":\"geckolib:winefox_hanfu\","
                + "\"headYaw\":-45,\"headPitch\":-20,\"fault\":\"legacy_sign\"}");
        assertEquals(-45, fault.yaw, 0);
        assertEquals(-20, fault.pitch, 0);
        assertEquals("legacy_sign", fault.fault);
        for (String suffix : new String[] { ",\"headYaw\":85.01", ",\"headYaw\":-85.01", ",\"headPitch\":90.01",
            ",\"headPitch\":-90.01", ",\"headYaw\":\"30\"", ",\"headPitch\":null", ",\"fault\":\"none\"" })
            assertThrows(
                IllegalArgumentException.class,
                () -> parse("{\"command\":\"head_binding_probe\",\"model\":\"geckolib:winefox_hanfu\"" + suffix + "}"));
        assertThrows(
            IllegalArgumentException.class,
            () -> parse(
                "{\"command\":\"head_binding_probe\",\"model\":\"authors_and_credits:wine_fox_maid\",\"fault\":\"legacy_sign\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"command\":\"head_binding_probe\"}"));
    }

    @Test
    public void gpuMatrixProbeUsesOnlyTwoModelsAndTheFixedSequence() {
        for (String model : new String[] { "geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid" }) {
            var request = parse("{\"command\":\"gpu_matrix_probe\",\"model\":\"" + model + "\"}");
            assertEquals(Command.GPU_MATRIX_PROBE, request.command);
            assertEquals(model, request.model);
        }
        for (String json : new String[] { "{\"command\":\"gpu_matrix_probe\"}",
            "{\"command\":\"gpu_matrix_probe\",\"model\":\"touhou_little_maid:hakurei_reimu\"}",
            "{\"command\":\"gpu_matrix_probe\",\"model\":\"geckolib:winefox_hanfu\",\"fault\":\"none\"}",
            "{\"command\":\"gpu_matrix_probe\",\"model\":\"geckolib:winefox_hanfu\",\"fault\":\"outer_scale_0_5\"}" })
            assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    @Test
    public void modelUiProbeActionsAndExplicitCaptureRemainRestricted() {
        for (String action : new String[] { "status", "open", "back", "next", "previous", "winefox", "all", "home",
            "home_toggle", "home_work", "home_idle", "home_rest", "home_reset", "tasks", "idle", "attack", "ranged",
            "pointer", "schedule", "pickup" })
            assertEquals(action, parse("{\"command\":\"models_ui\",\"action\":\"" + action + "\"}").action);
        var selection = parse("{\"command\":\"models_ui\",\"action\":\"select\",\"model\":\"geckolib:winefox_hanfu\"}");
        assertEquals("geckolib:winefox_hanfu", selection.model);
        assertEquals(
            "selector.png",
            parse("{\"command\":\"models_ui_capture\",\"name\":\"selector.png\"}").screenshotName);
        for (String json : new String[] { "{\"command\":\"models_ui\"}",
            "{\"command\":\"models_ui\",\"action\":\"eval\"}", "{\"command\":\"models_ui\",\"action\":\"select\"}",
            "{\"command\":\"models_ui_capture\",\"name\":\"../selector.png\"}" })
            assertThrows(IllegalArgumentException.class, () -> parse(json));
    }
}
