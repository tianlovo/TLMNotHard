package com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 实装后端的鼠标事件进入完整GuiScreen.handleInput，不修改任何模组的取槽准入标记 */
@SideOnly(Side.CLIENT)
public final class LwjglContainerMouseInput {

    private static final String PROVIDER = "3.0.42:sha256:63ee6de96b9d109edc38114058d8926155e012fc74d14cbf6e3ef2578af266c4";

    private LwjglContainerMouseInput() {}

    public static JsonObject click(Minecraft mc, GuiContainer gui, Slot slot, int button, int mode) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && gui == mc.currentScreen
                && mc.thePlayer != null
                && mc.thePlayer.openContainer == gui.inventorySlots,
            "Actual development GUI/container required");
        require(
            button >= 0 && button <= 1 && mode == 0,
            "Only ordinary native mouse clicks are supported; no synthetic Shift key state");
        var api = new Api();
        var trace = new Trace(gui);
        int window = gui.inventorySlots.windowId;
        int oldX = Mouse.getX(), oldY = Mouse.getY();
        boolean released = true, inputStarted = false;
        var diagnostics = new JsonObject();
        diagnostics.addProperty("window", window);
        diagnostics.addProperty("slot", slot.slotNumber);
        RuntimeException inputFailure = null;
        MinecraftForge.EVENT_BUS.register(trace);
        try {
            diagnostics.addProperty("phase", "preflight");
            boolean grabbed = Mouse.isGrabbed();
            int keyboardEvents = Keyboard.getNumKeyboardEvents();
            diagnostics.addProperty("mouseGrabbed", grabbed);
            diagnostics.addProperty("touchscreen", mc.gameSettings.touchscreen);
            diagnostics.addProperty("keyboardEvents", keyboardEvents);
            // This reviewed backend retains its current event. Keyboard.next advances only when size > 1.
            diagnostics.addProperty("pendingKeyboardEvents", keyboardEvents - 1);
            require(
                !grabbed && !mc.gameSettings.touchscreen && keyboardEvents == 1,
                "Unqueued ordinary GUI input required");
            diagnostics.addProperty("phase", "held-keys");
            for (int key = 1; key < 256; key++) require(!Keyboard.isKeyDown(key), "Foreign held key retained");
            diagnostics.addProperty("phase", "held-mouse-buttons");
            for (int mouse = 0; mouse < Mouse.getButtonCount(); mouse++)
                require(!Mouse.isButtonDown(mouse), "Foreign held mouse button retained");
            diagnostics.addProperty("phase", "mouse-queue");
            int pendingEvents = api.pending();
            diagnostics.addProperty("pendingEvents", pendingEvents);
            require(pendingEvents == 0, "Foreign native mouse events retained; wait for the normal input loop");
            diagnostics.addProperty("phase", "slot-coordinates");
            int left = integer(gui, "guiLeft", "field_147003_i"), top = integer(gui, "guiTop", "field_147009_r");
            int x = left + slot.xDisplayPosition + 8, y = top + slot.yDisplayPosition + 8;
            require(x >= 0 && y >= 0 && x < gui.width && y < gui.height, "Actual native slot is outside the screen");
            // Pixel centers round through GuiScreen's integer scaled-coordinate conversion to this exact slot.
            int pixelX = (int) ((x + .5) * mc.displayWidth / gui.width);
            int pixelTop = (int) ((y + .5) * mc.displayHeight / gui.height);
            inputStarted = true;
            diagnostics.addProperty("phase", "enqueue-move");
            api.move(pixelX, mc.displayHeight - pixelTop);
            diagnostics.addProperty("phase", "enqueue-press");
            api.button(button, true);
            released = false;
            diagnostics.addProperty("phase", "enqueue-release");
            api.button(button, false);
            released = true;
            // The transformed native loop dispatches ModularUI Pre -> original mouse handler -> Post.
            // It includes normal mod shortcut arbitration and native press/release handling.
            diagnostics.addProperty("phase", "native-dispatch");
            gui.handleInput();
            diagnostics.addProperty("phase", "native-event-trace");
            diagnostics.addProperty("preEvents", trace.pre);
            diagnostics.addProperty("postEvents", trace.post);
            diagnostics.addProperty("canceledEvents", trace.canceled);
            diagnostics.addProperty("traceFailure", trace.failure);
            int remainingEvents = api.pending();
            diagnostics.addProperty("remainingEvents", remainingEvents);
            require(
                remainingEvents == 0 && trace.failure == null
                    && trace.pre >= 3
                    && trace.post >= 3
                    && trace.canceled == 0,
                "Actual complete native GUI mouse event chain was not observed");
            diagnostics.addProperty("phase", "window-postcondition");
            require(
                mc.currentScreen == gui && mc.thePlayer.openContainer == gui.inventorySlots
                    && gui.inventorySlots.windowId == window
                    && gui.inventorySlots.getSlot(slot.slotNumber) == slot,
                "Native GUI/slot/window changed during input; no alternate click issued");
            var result = new JsonObject();
            result.addProperty("provider", PROVIDER);
            result.addProperty(
                "nativeEntry",
                "LWJGL addMoveEvent/addButtonEvent -> GuiScreen.handleInput -> ModularUI MouseInputEvent.Pre -> native mouse handler -> Post");
            result.addProperty("guiX", x);
            result.addProperty("guiY", y);
            result.addProperty("nativeMousePreEvents", trace.pre);
            result.addProperty("nativeMousePostEvents", trace.post);
            result.addProperty("nativeMouseCanceledEvents", trace.canceled);
            result.addProperty("shiftKeySynthesized", false);
            result.addProperty("serverAcknowledged", false);
            result.add("probeArtifact", DevelopmentArtifactIdentity.capture(LwjglContainerMouseInput.class));
            return result;
        } catch (Exception failure) {
            inputFailure = new IllegalStateException("Native container mouse input failed " + diagnostics, failure);
            throw inputFailure;
        } finally {
            try {
                if (!released) api.button(button, false);
                if (inputStarted && mc.thePlayer != null
                    && mc.currentScreen == gui
                    && mc.thePlayer.openContainer == gui.inventorySlots) {
                    // Restore pointer through the same backend, never by overwriting its private buffers.
                    api.move(oldX, oldY);
                    gui.handleInput();
                }
            } catch (RuntimeException cleanupFailure) {
                if (inputFailure == null) throw cleanupFailure;
                inputFailure.addSuppressed(cleanupFailure);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(trace);
            }
        }
    }

    private static int integer(GuiContainer gui, String... aliases) throws ReflectiveOperationException {
        for (String alias : aliases) try {
            var field = GuiContainer.class.getDeclaredField(alias);
            field.setAccessible(true);
            require(field.getType() == int.class, "Native GUI coordinate field changed shape");
            return field.getInt(gui);
        } catch (NoSuchFieldException missing) {
            // Fixed native field has mapped and packaged names.
        }
        throw new NoSuchFieldException("Native GUI origin unavailable");
    }

    public static final class Trace {

        final GuiContainer gui;
        int pre, post, canceled;
        String failure;

        Trace(GuiContainer gui) {
            this.gui = gui;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void input(Event event) {
            String name = event.getClass()
                .getName();
            if (!name.equals("com.cleanroommc.modularui.api.event.MouseInputEvent$Pre")
                && !name.equals("com.cleanroommc.modularui.api.event.MouseInputEvent$Post")) return;
            try {
                if (event.getClass()
                    .getField("gui")
                    .get(event) != gui) return;
                if (name.endsWith("$Pre")) {
                    pre++;
                    if (event.isCanceled()) canceled++;
                } else post++;
            } catch (ReflectiveOperationException invalid) {
                failure = invalid.toString();
            }
        }
    }

    private static final class Api {

        final Class<?> mouse, motion;
        final Method addMove, addButton;
        final Object queue;
        final Method eventCount;

        Api() {
            try {
                require(
                    PROVIDER.equals(DependencyFingerprint.mod("lwjgl3ify")),
                    "Reviewed native mouse provider required");
                mouse = Class.forName("org.lwjglx.input.Mouse");
                motion = Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");
                require(
                    DependencyFingerprint.source(
                        PackagedClassResourceResolver.artifactOf(mouse)
                            .toFile(),
                        "3.0.42")
                        .equals(PROVIDER),
                    "Actual mouse backend artifact differs from the reviewed provider");
                addMove = mouse.getMethod("addMoveEvent", motion);
                addButton = mouse.getMethod("addButtonEvent", int.class, boolean.class);
                var field = mouse.getDeclaredField("queue");
                field.setAccessible(true);
                queue = field.get(null);
                eventCount = queue.getClass()
                    .getDeclaredMethod("getEventCount");
                eventCount.setAccessible(true);
            } catch (Exception failure) {
                throw new IllegalStateException("Native mouse provider shape unavailable", failure);
            }
        }

        int pending() {
            try {
                return (Integer) eventCount.invoke(queue);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native mouse queue diagnosis failed", failure);
            }
        }

        void button(int button, boolean down) {
            try {
                addButton.invoke(null, button, down);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native mouse button enqueue failed", failure);
            }
        }

        void move(int x, int y) {
            try {
                var display = Class.forName("org.lwjglx.opengl.Display");
                float scale = (Float) display.getMethod("getPixelScaleFactor")
                    .invoke(null);
                int height = (Integer) display.getMethod("getHeight")
                    .invoke(null);
                require(scale > 0 && Float.isFinite(scale), "Native pixel scale unavailable");
                Object event = motion.getMethod("calloc")
                    .invoke(null);
                try {
                    motion.getMethod("x", float.class)
                        .invoke(event, (x + .25f) / scale);
                    motion.getMethod("y", float.class)
                        .invoke(event, (height - y + .25f) / scale);
                    // A native warp may leave a bounded ignore count. Submit through the real move API until consumed.
                    Field ignore = mouse.getDeclaredField("ignoreNextMove");
                    ignore.setAccessible(true);
                    int skipped = ignore.getInt(null);
                    require(skipped >= 0 && skipped <= 4, "Unknown native pending mouse warp retained");
                    for (int i = 0; i <= skipped; i++) addMove.invoke(null, event);
                    mouse.getMethod("poll")
                        .invoke(null);
                } finally {
                    motion.getMethod("free")
                        .invoke(event);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native mouse move enqueue failed", failure);
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
