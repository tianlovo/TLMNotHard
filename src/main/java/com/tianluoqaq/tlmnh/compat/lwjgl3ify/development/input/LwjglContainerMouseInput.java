package com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.development.MaidCareInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.ListenerList;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 实装后端的鼠标事件进入完整GuiScreen.handleInput，不修改任何模组的取槽准入标记 */
@SideOnly(Side.CLIENT)
public final class LwjglContainerMouseInput {

    private static final String PROVIDER = "3.0.42:sha256:63ee6de96b9d109edc38114058d8926155e012fc74d14cbf6e3ef2578af266c4";
    private static final String MODULAR_UI_PROVIDER = "2.3.94-1.7.10:sha256:5fee68e74b5d20eb5b2e8a5de668c74a1975bd0f8a6f463ed2be98844803dfdc";

    private LwjglContainerMouseInput() {}

    public static JsonObject click(Minecraft mc, GuiContainer gui, Slot slot, int button, int mode) {
        require(
            DevelopmentFlags.isDevCommandsEnabled() && gui == mc.currentScreen
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
        Throwable inputFailure = null;
        JsonObject result = null;
        TraceRegistration registration = null;
        diagnostics.add("beforeDispatch", frame(mc, gui));
        try {
            diagnostics.addProperty("phase", "listener-registration");
            registration = new TraceRegistration(trace);
            diagnostics.addProperty("listenerRegistration", "concrete-MouseInputEvent-Pre-and-Post");
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
            diagnostics.addProperty("nativeDispatchReturned", true);
            diagnostics.add("afterDispatch", frame(mc, gui));
            diagnostics.addProperty("phase", "native-event-trace");
            diagnostics.addProperty("preEvents", trace.pre);
            diagnostics.addProperty("postEvents", trace.post);
            diagnostics.addProperty("canceledEvents", trace.finalCanceled());
            diagnostics.addProperty("traceFailure", trace.failure);
            int remainingEvents = api.pending();
            diagnostics.addProperty("remainingEvents", remainingEvents);
            require(
                remainingEvents == 0 && trace.failure == null
                    && trace.pre >= 3
                    && trace.post >= 3
                    && trace.finalCanceled() == 0,
                "Actual complete native GUI mouse event chain was not observed");
            diagnostics.addProperty("phase", "window-postcondition");
            require(
                mc.currentScreen == gui && mc.thePlayer.openContainer == gui.inventorySlots
                    && gui.inventorySlots.windowId == window
                    && gui.inventorySlots.getSlot(slot.slotNumber) == slot,
                "Native GUI/slot/window changed during input; no alternate click issued");
            result = new JsonObject();
            result.addProperty("provider", PROVIDER);
            result.addProperty(
                "nativeEntry",
                "LWJGL addMoveEvent/addButtonEvent -> GuiScreen.handleInput -> ModularUI MouseInputEvent.Pre -> native mouse handler -> Post");
            result.addProperty("guiX", x);
            result.addProperty("guiY", y);
            result.addProperty("nativeMousePreEvents", trace.pre);
            result.addProperty("nativeMousePostEvents", trace.post);
            result.addProperty("nativeMouseCanceledEvents", trace.finalCanceled());
            result.addProperty("shiftKeySynthesized", false);
            result.addProperty("serverAcknowledged", false);
            result.add("probeArtifact", DevelopmentArtifactIdentity.capture(LwjglContainerMouseInput.class));
        } catch (Exception | LinkageError failure) {
            inputFailure = failure;
        } finally {
            diagnostics.add("afterInputAttempt", frame(mc, gui));
            // Event references are read only after EventBus.post has returned, including same-priority listeners.
            diagnostics.add("inputTrace", trace.snapshot());
            trace.phase = "pointer-restore";
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
                if (inputFailure == null) inputFailure = cleanupFailure;
                else inputFailure.addSuppressed(cleanupFailure);
            } finally {
                diagnostics.add("afterPointerRestore", frame(mc, gui));
                diagnostics.add("completeTrace", trace.snapshot());
                if (registration != null) try {
                    registration.close();
                    diagnostics.addProperty("listenersRemoved", true);
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    diagnostics.addProperty("listenersRemoved", false);
                    if (inputFailure == null) inputFailure = cleanupFailure;
                    else inputFailure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (inputFailure != null) throw new InputFailure(diagnostics, inputFailure);
        result.add("diagnostics", diagnostics);
        return result;
    }

    public static final class InputFailure extends IllegalStateException {

        private final JsonObject diagnostics;

        InputFailure(JsonObject diagnostics, Throwable cause) {
            super("Native container mouse input failed " + diagnostics, cause);
            this.diagnostics = diagnostics;
        }

        public JsonObject observation() {
            return diagnostics;
        }
    }

    private static String identity(Object value) {
        return value == null ? "null"
            : value.getClass()
                .getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    private static JsonObject frame(Minecraft mc, GuiContainer gui) {
        var result = new JsonObject();
        result.addProperty("suppliedGuiIdentity", identity(gui));
        result.addProperty("currentGuiIdentity", identity(mc.currentScreen));
        result.addProperty("sameGui", mc.currentScreen == gui);
        result.addProperty("guiEqualsCurrent", gui.equals(mc.currentScreen));
        result.addProperty("minecraftIdentity", identity(mc));
        result.addProperty("suppliedContainerIdentity", identity(gui.inventorySlots));
        result.addProperty("suppliedWindowId", gui.inventorySlots.windowId);
        result.addProperty(
            "currentContainerIdentity",
            mc.thePlayer == null ? "null" : identity(mc.thePlayer.openContainer));
        result.addProperty("currentWindowId", mc.thePlayer == null ? -1 : mc.thePlayer.openContainer.windowId);
        result.addProperty(
            "cursorNativeNbtBase64",
            mc.thePlayer == null ? "" : MaidCareInteractionProbe.nativeNbt(mc.thePlayer.inventory.getItemStack()));
        try {
            Minecraft bound = null;
            for (String name : new String[] { "mc", "field_146297_k" }) try {
                var field = GuiScreen.class.getDeclaredField(name);
                field.setAccessible(true);
                bound = (Minecraft) field.get(gui);
                break;
            } catch (NoSuchFieldException missing) {
                // Packaged and mapped names refer to the same native field.
            }
            result.addProperty("guiMinecraftIdentity", identity(bound));
            result
                .addProperty("guiMinecraftCurrentGuiIdentity", bound == null ? "null" : identity(bound.currentScreen));
            result.addProperty("sameGuiMinecraft", bound == mc);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            result.addProperty("guiMinecraftReadFailure", failure.toString());
        }
        return result;
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
        final List<ObservedEvent> events = new ArrayList<>();
        int pre, post, allPre, allPost;
        String phase = "input";
        String failure;

        Trace(GuiContainer gui) {
            this.gui = gui;
        }

        public void input(Event event) {
            String name = event.getClass()
                .getName();
            if (!name.equals("com.cleanroommc.modularui.api.event.MouseInputEvent$Pre")
                && !name.equals("com.cleanroommc.modularui.api.event.MouseInputEvent$Post")) return;
            try {
                Object eventGui = event.getClass()
                    .getField("gui")
                    .get(event);
                events.add(new ObservedEvent(event, eventGui, phase, gui));
                if (name.endsWith("$Pre")) {
                    allPre++;
                    if (phase.equals("input") && eventGui == gui) pre++;
                } else {
                    allPost++;
                    if (phase.equals("input") && eventGui == gui) post++;
                }
            } catch (ReflectiveOperationException invalid) {
                failure = invalid.toString();
            }
        }

        int finalCanceled() {
            int count = 0;
            for (var observed : events) if (observed.phase.equals("input") && observed.gui == gui
                && observed.event.getClass()
                    .getName()
                    .endsWith("$Pre")
                && observed.event.isCanceled()) count++;
            return count;
        }

        JsonObject snapshot() {
            var result = new JsonObject();
            result.addProperty("matchingInputPre", pre);
            result.addProperty("matchingInputPost", post);
            result.addProperty("allGuiPre", allPre);
            result.addProperty("allGuiPost", allPost);
            result.addProperty("matchingInputPreFinallyCanceled", finalCanceled());
            result.addProperty("failure", failure);
            var rows = new JsonArray();
            for (var observed : events) rows.add(observed.snapshot());
            result.add("events", rows);
            return result;
        }
    }

    private static final class TraceRegistration {

        final ListenerList pre, post;
        final IEventListener listener;
        final int busId;

        TraceRegistration(Trace trace) throws Exception {
            require(
                MODULAR_UI_PROVIDER.equals(DependencyFingerprint.mod("modularui2")),
                "Reviewed native GUI event provider required");
            var busField = EventBus.class.getDeclaredField("busID");
            require(busField.getType() == int.class, "Native Forge event bus ID changed shape");
            busField.setAccessible(true);
            busId = busField.getInt(MinecraftForge.EVENT_BUS);
            require(busId >= 0, "Native Forge event bus ID unavailable");
            pre = listeners("Pre", trace.gui);
            post = listeners("Post", trace.gui);
            require(pre != post, "Native GUI Pre/Post listener lists must be distinct");
            listener = trace::input;
            try {
                // A temporary parent-Event subscription leaves sibling caches stale after Pre rebuilds its parent.
                // Register both concrete lists so each native cache is independently invalidated, including Post.
                pre.register(busId, EventPriority.LOWEST, listener);
                post.register(busId, EventPriority.LOWEST, listener);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    close();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private static ListenerList listeners(String suffix, GuiScreen gui) throws Exception {
            var eventType = Class.forName("com.cleanroommc.modularui.api.event.MouseInputEvent$" + suffix)
                .asSubclass(Event.class);
            require(
                DependencyFingerprint.source(
                    PackagedClassResourceResolver.artifactOf(eventType)
                        .toFile(),
                    "2.3.94-1.7.10")
                    .equals(MODULAR_UI_PROVIDER),
                "Actual GUI event artifact differs from the reviewed provider");
            // Construction only obtains the native list. This event is never posted or used as an input.
            return eventType.getConstructor(GuiScreen.class)
                .newInstance(gui)
                .getListenerList();
        }

        void close() {
            Throwable failure = null;
            try {
                pre.unregister(busId, listener);
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure = cleanupFailure;
            }
            try {
                post.unregister(busId, listener);
            } catch (RuntimeException | LinkageError cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure != null) throw (LinkageError) failure;
        }
    }

    private static final class ObservedEvent {

        final Event event;
        final Object gui, dispatchGui;
        final String phase, currentGuiAtCallback;
        final boolean canceledAtCallback;
        final int button;
        final boolean buttonState;

        ObservedEvent(Event event, Object gui, String phase, Object dispatchGui) {
            this.event = event;
            this.gui = gui;
            this.phase = phase;
            this.dispatchGui = dispatchGui;
            currentGuiAtCallback = identity(Minecraft.getMinecraft().currentScreen);
            canceledAtCallback = event.isCanceled();
            button = Mouse.getEventButton();
            buttonState = Mouse.getEventButtonState();
        }

        JsonObject snapshot() {
            var result = new JsonObject();
            result.addProperty("phase", phase);
            result.addProperty("eventIdentity", identity(event));
            result.addProperty(
                "eventClass",
                event.getClass()
                    .getName());
            result.addProperty("eventGuiIdentity", identity(gui));
            result.addProperty("sameDispatchGui", gui == dispatchGui);
            result.addProperty("currentGuiIdentityAtCallback", currentGuiAtCallback);
            result.addProperty("currentGuiIdentityAfterDispatch", identity(Minecraft.getMinecraft().currentScreen));
            result.addProperty("sameCurrentGuiAfterDispatch", gui == Minecraft.getMinecraft().currentScreen);
            result.addProperty("cancelable", event.isCancelable());
            result.addProperty("canceledAtCallback", canceledAtCallback);
            result.addProperty("canceledAfterDispatch", event.isCanceled());
            result.addProperty("button", button);
            result.addProperty("buttonState", buttonState);
            return result;
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
