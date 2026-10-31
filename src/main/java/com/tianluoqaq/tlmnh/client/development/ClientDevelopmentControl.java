package com.tianluoqaq.tlmnh.client.development;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.opengl.GL11;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 显式开发开关下的游戏内测试驱动，不注入键鼠事件 */
@SideOnly(Side.CLIENT)
public final class ClientDevelopmentControl {

    private static final int MAX_REQUEST_BYTES = 16384;
    private static final int MAX_REQUESTS_PER_TICK = 8;
    private static final int MAX_PENDING_CAPTURES = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private final Minecraft minecraft;
    private final Path controls, responses;
    private final Queue<Capture> captures = new ArrayDeque<>();
    private final Map<String, ResponseWrite> pendingWrites = new LinkedHashMap<>();
    private Boolean previousPauseOnLostFocus;
    private boolean shutdownAfterWrites;
    private boolean submissionPending;
    private HeadBindingProbe.Result unwrittenHeadBinding;
    private GpuMatrixProbe.Result unwrittenGpuMatrix;
    private HeldItemGpuProbe.Result unwrittenHeldGpu;

    public ClientDevelopmentControl(Minecraft minecraft) throws IOException {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development controls disabled");
        this.minecraft = minecraft;
        var root = minecraft.mcDataDir.toPath()
            .resolve("tlmnh-dev");
        controls = root.resolve("control");
        responses = root.resolve("responses");
        for (var directory : new Path[] { root, controls, responses }) {
            if (Files.isSymbolicLink(directory)) throw new IOException("Development directory must not be a symlink");
            Files.createDirectories(directory);
        }
        TLMNotHard.LOG.info("TLMNH_CLIENT_CONTROL_READY directory=tlmnh-dev/control version={}", Tags.VERSION);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        flushResponses();
        HeadBindingProbe.tickEnd();
        flushHeadBinding();
        GpuMatrixProbe.tickEnd();
        flushGpuMatrix();
        HeldItemGpuProbe.tickEnd();
        flushHeldGpu();
        if (!AnimationLab.isLab(minecraft.theWorld)) restorePauseSetting();
        var submission = RenderSubmissionProbe.poll();
        if (submission != null) {
            submissionPending = false;
            submission.report.addProperty("version", Tags.VERSION);
            submission.report.addProperty("request", submission.request);
            submission.report.addProperty("command", "RENDER_PROBE");
            try {
                writeResponse(submission.request, submission.report);
            } catch (IOException error) {
                TLMNotHard.LOG.error("Cannot save render submission response", error);
            }
        }
        try (var stream = Files.newDirectoryStream(controls, "*.json")) {
            var pending = new ArrayList<Path>();
            for (var path : stream) if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) pending.add(path);
            pending.sort(
                Comparator.comparing(
                    path -> path.getFileName()
                        .toString()));
            for (int index = 0; index < Math.min(MAX_REQUESTS_PER_TICK, pending.size()); index++)
                handle(pending.get(index));
        } catch (IOException error) {
            TLMNotHard.LOG.error("Cannot read client development requests", error);
        }
        flushHeadBinding();
        flushGpuMatrix();
        if (shutdownAfterWrites && pendingWrites.isEmpty()
            && unwrittenHeadBinding == null
            && unwrittenGpuMatrix == null) minecraft.shutdown();
    }

    private void handle(Path path) throws IOException {
        String name = path.getFileName()
            .toString();
        var response = new JsonObject();
        response.addProperty("version", Tags.VERSION);
        response.addProperty("request", name);
        try {
            if (Files.size(path) > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request too large");
            var request = ClientDevelopmentRequest.parse(
                new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            response.addProperty("command", request.command.name());
            if ((HeadBindingProbe.isPending() || unwrittenHeadBinding != null)
                && request.command != ClientDevelopmentRequest.Command.STATUS
                && request.command != ClientDevelopmentRequest.Command.SHUTDOWN)
                throw new IllegalStateException("Finish the active head binding probe before another operation");
            if ((GpuMatrixProbe.isPending() || unwrittenGpuMatrix != null)
                && request.command != ClientDevelopmentRequest.Command.STATUS
                && request.command != ClientDevelopmentRequest.Command.SHUTDOWN)
                throw new IllegalStateException("Finish the active GPU matrix probe before another operation");
            if ((HeldItemGpuProbe.isPending() || unwrittenHeldGpu != null)
                && request.command != ClientDevelopmentRequest.Command.STATUS
                && request.command != ClientDevelopmentRequest.Command.SHUTDOWN)
                throw new IllegalStateException("Finish the active held GPU probe before another operation");
            switch (request.command) {
                case STATUS:
                    response.addProperty("worldLoaded", minecraft.theWorld != null);
                    response.addProperty("playerPresent", minecraft.thePlayer != null);
                    response.addProperty(
                        "screen",
                        minecraft.currentScreen == null ? "none"
                            : minecraft.currentScreen.getClass()
                                .getSimpleName());
                    response.addProperty("width", minecraft.displayWidth);
                    if (minecraft.currentScreen instanceof GuiDisconnected disconnected) {
                        try {
                            IChatComponent reason = ReflectionHelper
                                .getPrivateValue(GuiDisconnected.class, disconnected, "field_146304_f");
                            response
                                .addProperty("disconnectReason", reason == null ? null : reason.getUnformattedText());
                        } catch (RuntimeException error) {
                            response.addProperty(
                                "disconnectReasonUnavailable",
                                error.getClass()
                                    .getSimpleName());
                        }
                    }
                    response.addProperty("height", minecraft.displayHeight);
                    response.addProperty(
                        "headBindingPending",
                        HeadBindingProbe.isPending() || unwrittenHeadBinding != null);
                    response.addProperty("gpuMatrixPending", GpuMatrixProbe.isPending() || unwrittenGpuMatrix != null);
                    response
                        .addProperty("heldItemGpuPending", HeldItemGpuProbe.isPending() || unwrittenHeldGpu != null);
                    if (minecraft.thePlayer != null) {
                        var player = minecraft.thePlayer;
                        response.addProperty("dimension", player.dimension);
                        response.addProperty("x", player.posX);
                        response.addProperty("y", player.posY);
                        response.addProperty("z", player.posZ);
                        response.addProperty("yaw", player.rotationYaw);
                        response.addProperty("pitch", player.rotationPitch);
                        response.addProperty("health", player.getHealth());
                        response.addProperty("invulnerable", player.capabilities.disableDamage);
                        response.addProperty("allowFlying", player.capabilities.allowFlying);
                        response.addProperty("flying", player.capabilities.isFlying);
                        response.addProperty("noClip", player.noClip);
                        response.addProperty("animationLab", AnimationLab.isLab(player.worldObj));
                        response.addProperty(
                            "spectatorAbilities",
                            player.noClip && player.capabilities.disableDamage
                                && player.capabilities.isFlying
                                && !player.capabilities.isCreativeMode);
                    }
                    break;
                case CONNECT_LOCAL:
                    if (minecraft.theWorld != null || minecraft.thePlayer != null)
                        throw new IllegalStateException("Disconnect before starting a local test connection");
                    var handler = FMLClientHandler.instance();
                    handler.setupServerList();
                    handler.connectToServer(
                        new GuiMainMenu(),
                        new ServerData("TLMNH local test", "127.0.0.1:" + request.port));
                    response.addProperty("server", "127.0.0.1:" + request.port);
                    response.addProperty("connection", "requested");
                    break;
                case LAB_VIEW:
                    if (!AnimationLab.isLab(minecraft.theWorld) || minecraft.thePlayer == null
                        || !minecraft.thePlayer.capabilities.disableDamage
                        || !minecraft.thePlayer.capabilities.isFlying
                        || !minecraft.thePlayer.noClip)
                        throw new IllegalStateException("Safe animation lab observer required");
                    if (minecraft.currentScreen != null && !(minecraft.currentScreen instanceof GuiIngameMenu))
                        throw new IllegalStateException("Close the current screen before viewing the lab");
                    if (previousPauseOnLostFocus == null)
                        previousPauseOnLostFocus = minecraft.gameSettings.pauseOnLostFocus;
                    minecraft.gameSettings.pauseOnLostFocus = false;
                    minecraft.displayGuiScreen(null);
                    if (minecraft.currentScreen != null) {
                        restorePauseSetting();
                        throw new IllegalStateException("Lab view was cancelled");
                    }
                    response.addProperty("backgroundRendering", true);
                    break;
                case LOOK:
                    if (minecraft.thePlayer == null) throw new IllegalStateException("Player not loaded");
                    minecraft.thePlayer.rotationYaw = minecraft.thePlayer.prevRotationYaw = request.yaw;
                    minecraft.thePlayer.rotationPitch = minecraft.thePlayer.prevRotationPitch = request.pitch;
                    break;
                case SCREENSHOT:
                case MODELS_UI_CAPTURE:
                    if (minecraft.theWorld == null || minecraft.thePlayer == null)
                        throw new IllegalStateException("World not loaded");
                    boolean modelsUi = request.command == ClientDevelopmentRequest.Command.MODELS_UI_CAPTURE;
                    if (modelsUi) MaidModelUiProbe.requireCaptureScreen();
                    else if (minecraft.currentScreen != null)
                        throw new IllegalStateException("Screenshot obscured by screen");
                    if (captures.size() >= MAX_PENDING_CAPTURES)
                        throw new IllegalStateException("Screenshot queue full");
                    if (captures.stream()
                        .anyMatch(capture -> capture.filename.equals(request.screenshotName)))
                        throw new IllegalArgumentException("Screenshot already queued");
                    if (Files.exists(
                        minecraft.mcDataDir.toPath()
                            .resolve("screenshots")
                            .resolve(request.screenshotName)))
                        throw new IllegalArgumentException("Screenshot already exists");
                    captures.add(
                        new Capture(
                            name,
                            request.screenshotName,
                            modelsUi,
                            modelsUi ? minecraft.thePlayer.openContainer.windowId : -1));
                    response.addProperty("status", "pending-render");
                    break;
                case RELOAD:
                    minecraft.refreshResources();
                    break;
                case RENDER_PROBE:
                    RenderSubmissionProbe.arm(name, request.model, request.fault);
                    submissionPending = true;
                    response.addProperty("status", "pending-render");
                    break;
                case HEAD_BINDING_PROBE:
                    if (submissionPending) throw new IllegalStateException("Finish the active submission probe first");
                    HeadBindingProbe.arm(name, request.model, request.yaw, request.pitch, request.fault);
                    response.addProperty("status", "pending-render");
                    break;
                case GPU_MATRIX_PROBE:
                    if (submissionPending || !captures.isEmpty())
                        throw new IllegalStateException("Finish the active submission or screenshot capture first");
                    GpuMatrixProbe.arm(name, request.model);
                    response.addProperty("status", "pending-render");
                    break;
                case MODELS_UI:
                    var observed = MaidModelUiProbe.perform(request.action, request.model);
                    for (var entry : observed.entrySet()) response.add(entry.getKey(), entry.getValue());
                    break;
                case HELD_ITEM_GPU_PROBE:
                    if (submissionPending || !captures.isEmpty())
                        throw new IllegalStateException("Finish the active submission or screenshot first");
                    HeldItemGpuProbe.arm(name, request.model, request.action, request.view);
                    response.addProperty("status", "pending-render");
                    break;
                case GECKO_LIBRARY_PROBE:
                    response.add("library", com.tianluoqaq.tlmnh.common.development.MaidGeckoLibraryProbe.capture());
                    break;
                case SHUTDOWN:
                    HeadBindingProbe.cancel("Client shutdown requested during capture");
                    GpuMatrixProbe.cancel("Client shutdown requested during GPU capture");
                    HeldItemGpuProbe.cancel("Client shutdown requested during held GPU capture");
                    flushHeldGpu();
                    restorePauseSetting();
                    shutdownAfterWrites = true;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown client development command");
            }
            if (!response.has("status")) response.addProperty("status", "complete");
        } catch (IOException | RuntimeException error) {
            response.addProperty("status", "failed");
            response.addProperty("error", error.toString());
        }
        Files.move(path, path.resolveSibling(name + ".done"), StandardCopyOption.REPLACE_EXISTING);
        writeResponse(name, response);
        TLMNotHard.LOG.info(
            "TLMNH_CLIENT_CONTROL request={} result={}",
            name,
            response.get("status")
                .getAsString());
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        HeldItemGpuProbe.renderEnd(event.renderTickTime);
        if (captures.isEmpty()) return;
        var capture = captures.poll();
        var response = new JsonObject();
        response.addProperty("version", Tags.VERSION);
        response.addProperty("request", capture.request);
        response.addProperty("command", capture.modelsUi ? "MODELS_UI_CAPTURE" : "SCREENSHOT");
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            if (capture.modelsUi) {
                MaidModelUiProbe.requireCaptureScreen();
                if (minecraft.thePlayer.openContainer.windowId != capture.expectedWindow)
                    throw new IllegalStateException("Model selector window changed before capture");
                response.add("modelUiAtCapture", MaidModelUiProbe.observe());
            } else
                if (minecraft.currentScreen != null) throw new IllegalStateException("Screenshot obscured by screen");
            var path = minecraft.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(capture.filename);
            if (Files.exists(path)) throw new IOException("Screenshot already exists");
            ScreenShotHelper.saveScreenshot(
                minecraft.mcDataDir,
                capture.filename,
                minecraft.displayWidth,
                minecraft.displayHeight,
                minecraft.getFramebuffer());
            if (!Files.isRegularFile(path) || Files.size(path) == 0) throw new IOException("Screenshot not written");
            response.addProperty("status", "complete");
            response.addProperty("image", "screenshots/" + capture.filename);
            response.addProperty("width", minecraft.displayWidth);
            response.addProperty("height", minecraft.displayHeight);
        } catch (IOException | RuntimeException error) {
            response.addProperty("status", "failed");
            response.addProperty("error", error.toString());
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack);
        }
        try {
            writeResponse(capture.request, response);
        } catch (IOException error) {
            TLMNotHard.LOG.error("Cannot save screenshot response", error);
        }
        TLMNotHard.LOG.info(
            "TLMNH_CLIENT_CAPTURE image={} result={}",
            capture.filename,
            response.get("status")
                .getAsString());
    }

    private void restorePauseSetting() {
        if (previousPauseOnLostFocus != null) {
            minecraft.gameSettings.pauseOnLostFocus = previousPauseOnLostFocus;
            previousPauseOnLostFocus = null;
        }
    }

    private void flushHeadBinding() {
        if (unwrittenHeadBinding == null) unwrittenHeadBinding = HeadBindingProbe.poll();
        var result = unwrittenHeadBinding;
        if (result == null) return;
        result.report.addProperty("version", Tags.VERSION);
        result.report.addProperty("request", result.request);
        result.report.addProperty("command", "HEAD_BINDING_PROBE");
        try {
            writeResponse(result.request, result.report);
            unwrittenHeadBinding = null;
        } catch (IOException error) {
            TLMNotHard.LOG.error("Cannot save head binding response", error);
        }
    }

    private void flushGpuMatrix() {
        if (unwrittenGpuMatrix == null) unwrittenGpuMatrix = GpuMatrixProbe.poll();
        var result = unwrittenGpuMatrix;
        if (result == null) return;
        result.report.addProperty("version", Tags.VERSION);
        result.report.addProperty("request", result.request);
        result.report.addProperty("command", "GPU_MATRIX_PROBE");
        try {
            writeResponse(result.request, result.report);
            unwrittenGpuMatrix = null;
        } catch (IOException error) {
            TLMNotHard.LOG.error("Cannot save GPU matrix response", error);
        }
    }

    private void writeResponse(String name, JsonObject response) throws IOException {
        if (!pendingWrites.containsKey(name) && pendingWrites.size() >= 32)
            throw new IOException("Development response queue full");
        // 同一请求的最终结果覆盖尚未写出的pending，短暂共享冲突不丢弃结果
        pendingWrites.put(
            name,
            new ResponseWrite(
                GSON.toJson(response)
                    .getBytes(StandardCharsets.UTF_8)));
        flushResponses();
    }

    private void flushHeldGpu() {
        if (unwrittenHeldGpu == null) unwrittenHeldGpu = HeldItemGpuProbe.poll();
        var result = unwrittenHeldGpu;
        if (result == null) return;
        result.report.addProperty("version", Tags.VERSION);
        result.report.addProperty("request", result.request);
        result.report.addProperty("command", "HELD_ITEM_GPU_PROBE");
        try {
            writeResponse(result.request, result.report);
            unwrittenHeldGpu = null;
        } catch (IOException failure) {
            TLMNotHard.LOG.error("Cannot save held GPU response", failure);
        }
    }

    private void flushResponses() {
        var iterator = pendingWrites.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var pending = entry.getValue();
            try {
                var temporary = responses.resolve(entry.getKey() + ".tmp");
                Files.write(temporary, pending.bytes);
                Files.move(temporary, responses.resolve(entry.getKey()), StandardCopyOption.REPLACE_EXISTING);
                iterator.remove();
            } catch (IOException error) {
                if (System.nanoTime() > pending.deadline) {
                    TLMNotHard.LOG.error("Development response retry expired: {}", entry.getKey(), error);
                    iterator.remove();
                } else if (!pending.reported) {
                    TLMNotHard.LOG
                        .warn("Retrying development response after temporary I/O failure: {}", entry.getKey());
                    pending.reported = true;
                }
            }
        }
    }

    private static final class ResponseWrite {

        private final byte[] bytes;
        private final long deadline = System.nanoTime() + 20_000_000_000L;
        private boolean reported;

        private ResponseWrite(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private static final class Capture {

        private final String request, filename;
        private final boolean modelsUi;
        private final int expectedWindow;

        private Capture(String request, String filename, boolean modelsUi, int expectedWindow) {
            this.request = request;
            this.filename = filename;
            this.modelsUi = modelsUi;
            this.expectedWindow = expectedWindow;
        }
    }
}
