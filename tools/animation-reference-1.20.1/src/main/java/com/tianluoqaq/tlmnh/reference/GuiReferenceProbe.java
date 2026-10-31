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
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.AbstractModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.IModelInfo;
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
        var screen = minecraft.screen;
        if (!(screen instanceof AbstractMaidContainerGui<?> || screen instanceof MaidModelGui) || minecraft.player == null)
            throw new IllegalStateException("Actual original maid GUI required");
        var maid = screen instanceof AbstractMaidContainerGui<?> main ? main.getMenu().getMaid()
            : (EntityMaid) field(AbstractModelGui.class, screen, "entity");
        if (!ReferenceScene.isCurrent(maid) || !maid.isOwnedBy(minecraft.player) || !minecraft.player.isSpectator())
            throw new IllegalStateException("Owned reference lab maid and spectator required");
        var result = new JsonObject();
        result.addProperty("screen", screen.getClass().getName());
        result.addProperty("model", maid.getModelId());
        result.addProperty("windowId", minecraft.player.containerMenu.containerId);
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
        for (var slot : minecraft.player.containerMenu.slots) {
            var entry = new JsonObject();
            entry.addProperty("index", slot.index);
            entry.addProperty("x", slot.x);
            entry.addProperty("y", slot.y);
            slots.add(entry);
        }
        result.add("slots", slots);
        if (screen instanceof MaidModelGui) {
            int pack = (Integer) field(MaidModelGui.class, null, "PACK_INDEX");
            int row = (Integer) field(MaidModelGui.class, null, "ROW_INDEX");
            result.addProperty("pack", com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader.MAID_MODELS.getPackList().get(pack).getId());
            result.addProperty("row", row);
            result.addProperty("searchMode", (Boolean) field(AbstractModelGui.class, screen, "isSearchMode"));
            result.addProperty("searchText", (String) field(AbstractModelGui.class, screen, "searchText"));
            result.addProperty("iconCache", com.github.tartaricacid.touhoulittlemaid.config.subconfig.MiscConfig.MODEL_ICON_CACHE.get());
            try {
                var method = AbstractModelGui.class.getDeclaredMethod("getDisplayModelList");
                method.setAccessible(true);
                var models = (java.util.List<?>) method.invoke(screen);
                var visible = new JsonArray();
                for (int index = row * 11; index < Math.min(models.size(), row * 11 + 55); index++)
                    visible.add(((IModelInfo) models.get(index)).getModelId().toString());
                result.add("visibleModels", visible);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot observe original model list", error);
            }
        }
        return result;
    }

    private static Object field(Class<?> type, Object owner, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read original GUI field: " + name, error);
        }
    }

    static JsonObject models() {
        status();
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractMaidContainerGui<?> main))
            throw new IllegalStateException("Original main window required");
        com.github.tartaricacid.touhoulittlemaid.config.subconfig.MiscConfig.MODEL_ICON_CACHE.set(false);
        com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager.openMaidModelGui(main.getMenu().getMaid());
        return status();
    }

    static JsonObject modelsPack(JsonObject request) {
        status();
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof MaidModelGui screen)) throw new IllegalStateException("Original model screen required");
        int pack = request.get("pack").getAsInt();
        if (pack < 0 || pack >= Math.min(7, com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader.MAID_MODELS.getPackList().size()))
            throw new IllegalArgumentException("Invalid builtin pack tab");
        screen.mouseClicked(screen.width / 2 + 50 - 98 + 28 * pack + 14, screen.height / 2 - 100, 0);
        return status();
    }

    static JsonObject modelsSearch(JsonObject request) {
        status();
        var minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof MaidModelGui screen)) throw new IllegalStateException("Original model screen required");
        String query = request.get("query").getAsString();
        if (query.length() > 48) throw new IllegalArgumentException("Search query too long");
        if (!(Boolean) field(AbstractModelGui.class, screen, "isSearchMode"))
            screen.mouseClicked(screen.width / 2 + 50 - 121 + 12, screen.height / 2 + 105, 0);
        ((net.minecraft.client.gui.components.EditBox) field(AbstractModelGui.class, screen, "searchBox")).setValue(query);
        return status();
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
