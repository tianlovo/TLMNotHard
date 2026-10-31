package com.tianluoqaq.tlmnh.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 仅在原版参考虚空场景打开真实菜单并采集实际帧，不替换原版界面 */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class GuiReferenceProbe {

    private static Pending pending;

    private GuiReferenceProbe() {}

    static JsonObject open() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isSpectator() || minecraft.getSingleplayerServer() == null)
            throw new IllegalStateException("Reference spectator and integrated lab required");
        // 固定真实测试窗口的指针，避免中心指针遮住饰品按钮
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), 0, 0);
        com.github.tartaricacid.touhoulittlemaid.client.download.InfoGetManager.STATUE =
            com.github.tartaricacid.touhoulittlemaid.client.download.InfoGetManager.Statue.NOT_UPDATE;
        var scene = ReferenceScene.status();
        if (!"ready".equals(scene.get("status").getAsString())) throw new IllegalStateException("Reference scene not ready");
        UUID entity = UUID.fromString(scene.get("entityUuid").getAsString());
        UUID owner = minecraft.player.getUUID();
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(owner);
            var actual = server.overworld().getEntity(entity);
            if (!(actual instanceof EntityMaid maid) || !ReferenceScene.isCurrent(maid) || player == null
                || !maid.isOwnedBy(player) || !player.isSpectator())
                throw new IllegalStateException("Actual owned lab maid required");
            maid.setInSittingPose(true);
            maid.setHomeModeEnable(true);
            maid.setPickup(true);
            maid.openMaidGui(player);
        });
        var response = new JsonObject();
        response.addProperty("openRequested", true);
        response.addProperty("serverAcknowledged", false);
        return response;
    }

    static JsonObject status() {
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractMaidContainerGui<?> screen) || minecraft.player == null
            || screen.getMenu() != minecraft.player.containerMenu)
            throw new IllegalStateException("Actual original maid GUI required");
        var maid = screen.getMenu().getMaid();
        if (!ReferenceScene.isCurrent(maid) || !maid.isOwnedBy(minecraft.player) || !minecraft.player.isSpectator())
            throw new IllegalStateException("Owned reference lab maid and spectator required");
        var result = new JsonObject();
        result.addProperty("screen", screen.getClass().getName());
        result.addProperty("model", maid.getModelId());
        result.addProperty("windowId", screen.getMenu().containerId);
        result.addProperty("language", minecraft.options.languageCode);
        result.addProperty("guiScale", minecraft.getWindow().getGuiScale());
        result.addProperty("scaledWidth", screen.width);
        result.addProperty("scaledHeight", screen.height);
        result.addProperty("width", minecraft.getWindow().getWidth());
        result.addProperty("height", minecraft.getWindow().getHeight());
        result.addProperty("mouseX", minecraft.mouseHandler.xpos());
        result.addProperty("mouseY", minecraft.mouseHandler.ypos());
        result.addProperty("sitting", maid.isInSittingPose());
        result.addProperty("home", maid.isHomeModeEnable());
        result.addProperty("pickup", maid.isPickup());
        var slots = new JsonArray();
        for (var slot : screen.getMenu().slots) {
            var entry = new JsonObject();
            entry.addProperty("index", slot.index);
            entry.addProperty("x", slot.x);
            entry.addProperty("y", slot.y);
            slots.add(entry);
        }
        result.add("slots", slots);
        return result;
    }

    static JsonObject capture(JsonObject request, Path root, String name) {
        if (pending != null) throw new IllegalStateException("GUI capture already pending");
        status();
        String filename = request.get("name").getAsString();
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.png"))
            throw new IllegalArgumentException("Invalid GUI screenshot filename");
        pending = new Pending(root, name, filename, Minecraft.getInstance().player.containerMenu.containerId);
        var response = new JsonObject();
        response.addProperty("status", "waiting-render");
        return response;
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.referenceProbe") || event.phase != TickEvent.Phase.END || pending == null) return;
        var capture = pending;
        pending = null;
        var response = new JsonObject();
        try {
            var snapshot = status();
            if (snapshot.get("windowId").getAsInt() != capture.window()) throw new IllegalStateException("GUI window changed");
            var image = capture.root().resolve("gui").resolve(capture.filename());
            Files.createDirectories(image.getParent());
            if (Files.exists(image)) throw new IOException("GUI capture already exists");
            try (var pixels = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                pixels.writeToFile(image);
            }
            response.addProperty("status", "complete");
            response.addProperty("image", "gui/" + capture.filename());
            response.add("guiAtCapture", snapshot);
            response.add("runtimeIdentity", ReferenceRuntimeIdentity.capture().runtimeIdentity());
        } catch (IOException | RuntimeException error) {
            response.addProperty("status", "failed");
            response.addProperty("error", error.toString());
        }
        try {
            var target = capture.root().resolve("responses").resolve(capture.request());
            var temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().serializeNulls().create().toJson(response) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot save original GUI capture response", error);
        }
    }

    private record Pending(Path root, String request, String filename, int window) {}
}
