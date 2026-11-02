package com.tianluoqaq.tlmnh.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.block.BlockShrine;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityShrine;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 固定原版神龛观察：只操作已登记的自有合成场景，不修改原渲染器或胶片使用逻辑 */
@Mod.EventBusSubscriber(modid = TouhouLittleMaid.MOD_ID, value = Dist.CLIENT)
public final class ShrineReferenceProbe {

    private static final String WORLD = "TLMNH-Reference-Lab", DATA = "TLMNH_ShrineReferenceFixture";
    private static final int[] X = {-4, 0, 4};
    private static volatile String state = "idle", failure = "";
    private static Pending pending;

    private ShrineReferenceProbe() {}

    private static Minecraft requireLab() {
        var mc = Minecraft.getInstance();
        require(Boolean.getBoolean("tlmnh.referenceProbe") && mc.level != null && mc.player != null
            && mc.player.isAlive() && mc.player.isSpectator() && mc.screen == null
            && mc.getSingleplayerServer() != null && WORLD.equals(mc.getSingleplayerServer().getWorldData().getLevelName()),
            "Current original protected spectator lab required");
        return mc;
    }

    static JsonObject fixture(JsonObject request) {
        var mc = requireLab();
        String action = request.get("action").getAsString();
        require(action.equals("open") || action.equals("clear"), "Unknown original shrine fixture action");
        require(pending == null && !state.equals("queued"), "Original shrine request already pending");
        UUID owner = mc.player.getUUID();
        state = "queued";
        failure = "";
        mc.options.pauseOnLostFocus = false;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayer(owner);
                var level = server.overworld();
                require(player != null && player.isSpectator() && player.serverLevel() == level,
                    "Original server spectator required");
                var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA + ".dat");
                var journal = level.getDataStorage().computeIfAbsent(Journal::load, Journal::new, DATA);
                require(journal.readComplete && (!Files.exists(file) || !journal.newlyCreated),
                    "Unreadable original shrine journal retained");
                journal.newlyCreated = false;
                require(journal.owner.isEmpty() || journal.owner.equals(owner.toString()), "Other observer owns original shrine fixture");
                if (!journal.owner.isEmpty()) {
                    validate(level, journal.ready);
                    journal.ready = false;
                    journal.setDirty();
                    for (int x : X) {
                        var pos = new BlockPos(x, 65, -2);
                        if (level.getBlockEntity(pos) instanceof TileEntityShrine shrine) shrine.extractStorageItem();
                        if (level.getBlockState(pos).is(InitBlocks.SHRINE.get())) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                    journal.owner = "";
                    journal.setDirty();
                }
                if (action.equals("open")) {
                    for (int x : X) for (int y = 65; y <= 66; y++) {
                        var pos = new BlockPos(x, y, -2);
                        var floor = level.getBlockState(new BlockPos(x, 64, -2));
                        require(level.isEmptyBlock(pos) && level.getBlockEntity(pos) == null
                            && (floor.is(Blocks.GRAY_WOOL) || floor.is(Blocks.LIGHT_GRAY_WOOL)), "Original fixture area is not empty lab floor");
                    }
                    journal.owner = owner.toString();
                    journal.setDirty();
                    for (int i = 0; i < X.length; i++) {
                        var pos = new BlockPos(X[i], 65, -2);
                        require(level.setBlock(pos, InitBlocks.SHRINE.get().defaultBlockState()
                            .setValue(BlockShrine.FACING, i == 2 ? Direction.EAST : Direction.NORTH), 2), "Original shrine placement failed");
                        if (i > 0) ((TileEntityShrine) level.getBlockEntity(pos)).insertStorageItem(film());
                    }
                    journal.ready = true;
                    journal.setDirty();
                    validate(level, true);
                }
                state = action.equals("open") ? "ready" : "cleared";
            } catch (RuntimeException error) {
                failure = error.toString();
                state = "failed";
                TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_SHRINE_FAILED", error);
            }
        });
        return snapshot();
    }

    static JsonObject view(JsonObject request) {
        var mc = requireLab();
        require(state.equals("ready") && pending == null, "Ready original shrine fixture required");
        String action = request.get("action").getAsString();
        double x, y, z;
        float yaw, pitch;
        switch (action) {
            case "front" -> { x = .5; y = 66; z = -10; yaw = 0; pitch = 16; }
            case "close" -> { x = .5; y = 64.3; z = -3.7; yaw = 0; pitch = 0; }
            case "inside" -> { x = .5; y = 64.3; z = -1.7; yaw = 0; pitch = 0; }
            case "side" -> { x = 9; y = 66; z = -2; yaw = 90; pitch = 15; }
            default -> throw new IllegalArgumentException("Unknown fixed original shrine view");
        }
        UUID owner = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(owner);
            require(player != null && player.isSpectator(), "Original observer no longer spectator");
            player.connection.teleport(x, y, z, yaw, pitch);
        });
        return snapshot();
    }

    static JsonObject snapshot() {
        var mc = Minecraft.getInstance();
        var result = new JsonObject();
        result.addProperty("fixture", state);
        result.addProperty("error", failure);
        var tiles = new JsonArray();
        if (mc.level != null) for (int x : X) {
            var pos = new BlockPos(x, 65, -2);
            if (!(mc.level.getBlockEntity(pos) instanceof TileEntityShrine shrine)) continue;
            var tile = new JsonObject();
            tile.addProperty("x", x);
            tile.addProperty("facing", mc.level.getBlockState(pos).getValue(BlockShrine.FACING).getName());
            var item = shrine.getStorageItem();
            tile.addProperty("film", item.is(InitItems.FILM.get()));
            tile.addProperty("count", item.getCount());
            tiles.add(tile);
        }
        result.add("shrines", tiles);
        if (mc.player != null) {
            result.addProperty("x", mc.player.getX()); result.addProperty("y", mc.player.getY()); result.addProperty("z", mc.player.getZ());
            result.addProperty("yaw", mc.player.getYRot()); result.addProperty("pitch", mc.player.getXRot());
            result.addProperty("spectator", mc.player.isSpectator());
        }
        return result;
    }

    static JsonObject capture(JsonObject request, Path root, String name) {
        var mc = requireLab();
        require(state.equals("ready") && pending == null, "Ready original shrine capture required");
        String filename = request.get("name").getAsString();
        require(filename.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.png"), "Invalid original world screenshot name");
        pending = new Pending(root, name, filename, mc.level);
        var result = new JsonObject(); result.addProperty("status", "waiting-render"); return result;
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        var capture = pending; pending = null;
        var result = new JsonObject();
        try {
            var mc = requireLab();
            require(mc.level == capture.level(), "Original lab world changed while waiting for render");
            var image = capture.root().resolve("world").resolve(capture.filename());
            Files.createDirectories(image.getParent());
            require(!Files.exists(image), "Original world screenshot already exists");
            try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
            result.addProperty("status", "complete");
            result.addProperty("image", "world/" + capture.filename());
            result.add("shrineAtCapture", snapshot());
            result.add("runtimeIdentity", ReferenceRuntimeIdentity.capture().runtimeIdentity());
        } catch (IOException | RuntimeException error) {
            result.addProperty("status", "failed"); result.addProperty("error", error.toString());
        }
        try {
            var target = capture.root().resolve("responses").resolve(capture.request());
            var temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().serializeNulls().create().toJson(result) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_SHRINE_CAPTURE_FAILED", error); }
    }

    private static void validate(ServerLevel level, boolean ready) {
        for (int i = 0; i < X.length; i++) {
            var pos = new BlockPos(X[i], 65, -2);
            var block = level.getBlockState(pos);
            if (level.isEmptyBlock(pos)) require(!ready && level.getBlockEntity(pos) == null, "Ready original shrine removed; retained");
            else {
                require(block.is(InitBlocks.SHRINE.get()) && block.getValue(BlockShrine.FACING) == (i == 2 ? Direction.EAST : Direction.NORTH)
                    && level.getBlockEntity(pos) instanceof TileEntityShrine, "Changed original shrine retained");
                var item = ((TileEntityShrine) level.getBlockEntity(pos)).getStorageItem();
                require(i == 0 ? item.isEmpty() : ItemStack.isSameItemSameTags(item, film()) && item.getCount() == 1 || !ready && item.isEmpty(),
                    "Unexpected original shrine storage retained");
            }
            require(level.isEmptyBlock(pos.above()) && level.getBlockEntity(pos.above()) == null, "Changed original shrine upper area retained");
        }
    }

    private static ItemStack film() {
        var item = new ItemStack(InitItems.FILM.get());
        item.getOrCreateTag().putString("TLMNHFixture", "ShrineReferenceProbe");
        return item;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private record Pending(Path root, String request, String filename, ClientLevel level) {}

    public static final class Journal extends SavedData {
        String owner = "";
        boolean ready, newlyCreated = true, readComplete = true;
        CompoundTag raw;
        static Journal load(CompoundTag tag) {
            var data = new Journal(); data.newlyCreated = false; data.raw = tag.copy(); data.readComplete = false;
            if (!tag.contains("Schema", 3) || tag.getInt("Schema") != 1 || !tag.contains("Owner", 8) || !tag.contains("Ready", 1)) return data;
            data.owner = tag.getString("Owner"); data.ready = tag.getBoolean("Ready");
            try { if (!data.owner.isEmpty()) UUID.fromString(data.owner); }
            catch (IllegalArgumentException error) { return data; }
            data.readComplete = !data.ready || !data.owner.isEmpty(); return data;
        }
        @Override public CompoundTag save(CompoundTag tag) {
            if (!readComplete && raw != null) return raw.copy();
            tag.putInt("Schema", 1); tag.putString("Owner", owner); tag.putBoolean("Ready", ready); return tag;
        }
    }
}
