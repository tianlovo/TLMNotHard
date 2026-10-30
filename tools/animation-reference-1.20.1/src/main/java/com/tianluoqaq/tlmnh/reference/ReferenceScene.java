package com.tianluoqaq.tlmnh.reference;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;

/** 仅参考运行目录内的集成服虚空场景，不修改原版动画实现 */
final class ReferenceScene {
    private static final String WORLD = "TLMNH-Reference-Lab";
    private static final String MARKER = "TLMNHReferenceScene";
    private static volatile State state = new State("idle", null, -1, null);

    private ReferenceScene() {}

    static JsonObject status() {
        var current = state;
        var result = new JsonObject();
        result.addProperty("status", current.status());
        result.addProperty("entityUuid", current.entity() == null ? null : current.entity().toString());
        result.addProperty("entityId", current.entityId());
        result.addProperty("error", current.error());
        return result;
    }

    static boolean isCurrent(EntityMaid maid) {
        var current = state;
        return "ready".equals(current.status()) && maid.getUUID().equals(current.entity()) && maid.getId() == current.entityId();
    }

    static String failure(UUID entity) {
        var current = state;
        return "failed".equals(current.status()) && entity.equals(current.entity()) ? current.error() : null;
    }

    static JsonObject openLab() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.getSingleplayerServer() != null) throw new IllegalStateException("Close the current world first");
        if (!Files.isRegularFile(minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("level.dat")))
            throw new IllegalStateException("Reference world does not exist");
        minecraft.options.pauseOnLostFocus = false;
        minecraft.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
        var response = new JsonObject();
        response.addProperty("world", "opening-requested");
        response.addProperty("ready", false);
        return response;
    }

    static JsonObject clear() {
        var minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        if (server == null || !WORLD.equals(server.getWorldData().getLevelName())) throw new IllegalStateException("Reference lab required");
        state = new State("clearing", null, -1, null);
        server.execute(() -> {
            try {
                for (var maid : server.overworld().getEntities(EntityTypeTest.forClass(EntityMaid.class), entity -> entity.getTags().contains(MARKER))) maid.discard();
                state = new State("idle", null, -1, null);
            } catch (RuntimeException error) {
                state = new State("failed", null, -1, error.toString());
            }
        });
        var response = new JsonObject();
        response.addProperty("scene", "clear-requested");
        response.addProperty("ready", false);
        return response;
    }

    static JsonObject createLab() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.getSingleplayerServer() != null) throw new IllegalStateException("Close the current world first");
        if (Files.exists(minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD))) throw new IllegalStateException("Reference world already exists; no overwrite was performed");
        minecraft.options.pauseOnLostFocus = false;
        var settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true,
            new GameRules(), WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0x544c4d4e48L, false, false), access -> {
            var biome = access.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
            var flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biome, List.of());
            flat.updateLayers();
            return WorldPresets.createNormalWorldDimensions(access).replaceOverworldGenerator(access, new FlatLevelSource(flat));
        });
        var response = new JsonObject();
        response.addProperty("world", "creation-requested");
        response.addProperty("ready", false);
        return response;
    }

    static JsonObject spawn(JsonObject request) {
        return spawn(request, UUID.randomUUID(), () -> {});
    }

    static JsonObject spawn(JsonObject request, UUID entity, Runnable beforeSpawn) {
        var minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null || minecraft.level == null) throw new IllegalStateException("Integrated reference lab is not ready");
        if (!WORLD.equals(server.getWorldData().getLevelName())) throw new IllegalStateException("Unexpected reference world");
        String model = request.get("model").getAsString();
        if (CustomPackLoader.MAID_MODELS.getInfo(model).isEmpty()) throw new IllegalArgumentException("Unknown original builtin model");
        UUID owner = minecraft.player.getUUID();
        state = new State("queued", entity, -1, null);
        minecraft.options.pauseOnLostFocus = false;
        minecraft.setScreen(null);
        beforeSpawn.run();
        server.execute(() -> {
            try {
                var level = server.overworld();
                if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource)) throw new IllegalStateException("Reference world is not the expected air generator");
                for (int x : new int[] {-32, 32}) for (int z : new int[] {-32, 32}) {
                    level.getChunk(x >> 4, z >> 4);
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++)
                        if (!level.isEmptyBlock(new BlockPos(x, y, z))) throw new IllegalStateException("Reference lab is not void");
                }
                for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                    var position = new BlockPos(x, 64, z);
                    var block = ((x + z) & 1) == 0 ? Blocks.GRAY_WOOL.defaultBlockState() : Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
                    level.setBlock(position, block, 2);
                    if (!level.getBlockState(position).equals(block)) throw new IllegalStateException("Missing reference platform block");
                }
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                for (var previous : level.getEntities(EntityTypeTest.forClass(EntityMaid.class), maid -> maid.getTags().contains(MARKER))) previous.discard();
                var maid = new EntityMaid(level);
                maid.setUUID(entity);
                maid.addTag(MARKER);
                maid.setOwnerUUID(owner);
                maid.setTame(true);
                maid.setNoAi(true);
                maid.setInvulnerable(true);
                maid.setPersistenceRequired();
                maid.setModelId(model);
                maid.moveTo(0.5, 65, 0.5, 180, 0);
                maid.yBodyRotO = maid.yBodyRot = maid.yHeadRotO = maid.yHeadRot = 180;
                if (!level.addFreshEntity(maid)) throw new IllegalStateException("Original maid spawn rejected");
                var player = server.getPlayerList().getPlayer(owner);
                if (player == null) throw new IllegalStateException("Reference observer not found");
                player.setGameMode(GameType.SPECTATOR);
                player.setHealth(player.getMaxHealth());
                player.teleportTo(level, 0.5, 65, -4.5, 0, 10);
                if (!player.isSpectator()) throw new IllegalStateException("Reference observer is not a spectator");
                state = new State("ready", entity, maid.getId(), null);
                TouhouLittleMaid.LOGGER.info("TLMNH_REFERENCE_SCENE_READY entity={} model={} void=true mode=spectator", entity, model);
            } catch (RuntimeException error) {
                state = new State("failed", entity, -1, error.toString());
                TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_SCENE_FAILED entity={}", entity, error);
            }
        });
        var response = new JsonObject();
        response.addProperty("scene", "requested");
        response.addProperty("entityUuid", entity.toString());
        response.addProperty("ready", false);
        return response;
    }

    private record State(String status, UUID entity, int entityId, String error) {}
}
