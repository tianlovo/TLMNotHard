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
import java.util.Queue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
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
                    response.addProperty("height", minecraft.displayHeight);
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
                case LOOK:
                    if (minecraft.thePlayer == null) throw new IllegalStateException("Player not loaded");
                    minecraft.thePlayer.rotationYaw = minecraft.thePlayer.prevRotationYaw = request.yaw;
                    minecraft.thePlayer.rotationPitch = minecraft.thePlayer.prevRotationPitch = request.pitch;
                    break;
                case SCREENSHOT:
                    if (minecraft.theWorld == null || minecraft.thePlayer == null)
                        throw new IllegalStateException("World not loaded");
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
                    captures.add(new Capture(name, request.screenshotName));
                    response.addProperty("status", "pending-render");
                    break;
                case RELOAD:
                    minecraft.refreshResources();
                    break;
                case SHUTDOWN:
                    minecraft.shutdown();
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
        if (event.phase != TickEvent.Phase.END || captures.isEmpty()) return;
        var capture = captures.poll();
        var response = new JsonObject();
        response.addProperty("version", Tags.VERSION);
        response.addProperty("request", capture.request);
        response.addProperty("command", "SCREENSHOT");
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
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

    private void writeResponse(String name, JsonObject response) throws IOException {
        var temporary = responses.resolve(name + ".tmp");
        Files.write(
            temporary,
            GSON.toJson(response)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, responses.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class Capture {

        private final String request, filename;

        private Capture(String request, String filename) {
            this.request = request;
            this.filename = filename;
        }
    }
}
