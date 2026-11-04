package com.tianluoqaq.tlmnh.reference;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.openal.AL10;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.AbstractModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.IModelInfo;
import com.github.tartaricacid.touhoulittlemaid.client.sound.CustomSoundLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstance;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.SoundData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.audio.SoundBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One original maid, actual model-button selection and original native voice playback. Never replaces sound data. */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class ReferenceAudioProbe {
    private static final String TARGET_MODEL = "geckolib:winefox_magical";
    private static final String INITIAL_MODEL = "touhou_little_maid:hakurei_reimu";
    private static volatile Session active;
    private static Field pcmField, formatField, channelSourceField;
    private static final List<Loaded> LOADED = new ArrayList<>();
    private static final JsonArray LOAD_FAILURES = new JsonArray();

    private ReferenceAudioProbe() {}

    static JsonObject command(JsonObject request, Path root) {
        require(Boolean.getBoolean("tlmnh.referenceProbe"), "Reference probe is disabled");
        String action = request.get("action").getAsString();
        if (action.equals("prepare")) return prepare(root);
        Session session = requireSession();
        return switch (action) {
            case "status" -> status(session);
            case "open" -> GuiReferenceProbe.open();
            case "models" -> {
                require(Minecraft.getInstance().screen instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui<?>,
                    "Original main GUI required");
                com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache.CacheIconManager.openMaidModelGui(maid(session));
                yield status(session);
            }
            case "select_magic" -> selectMagic(session);
            case "play" -> play(session);
            case "restore" -> restore(session);
            default -> throw new IllegalArgumentException("Unknown bounded audio-reference action");
        };
    }

    private static JsonObject prepare(Path root) {
        require(active == null, "Restore the previous audio reference first");
        var mc = Minecraft.getInstance();
        require(mc.level != null && mc.player != null && mc.getSingleplayerServer() != null
            && mc.screen == null && mc.player.isSpectator(), "Unobscured reference spectator lab required");
        require("idle".equals(ReferenceScene.status().get("status").getAsString()), "Clear the existing reference scene first");
        for (var entity : mc.level.entitiesForRendering())
            require(!(entity instanceof EntityMaid), "Existing maid cannot be replaced by this audio fixture");
        var session = new Session(root, UUID.randomUUID(), mc.player.getUUID());
        session.resourcePacks = List.copyOf(mc.getResourcePackRepository().getSelectedIds());
        mc.getSingleplayerServer().submit(() -> {
            var level = mc.getSingleplayerServer().overworld();
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(session.owner);
            require(player != null && player.isSpectator() && player.getVehicle() == null && player.getPassengers().isEmpty()
                && player.getHealth() == player.getMaxHealth(), "Unridden healthy reference spectator required");
            session.playerPosition = player.position();
            session.playerVelocity = player.getDeltaMovement();
            session.yaw = player.getYRot();
            session.pitch = player.getXRot();
            session.playerInventory = player.getInventory().save(new ListTag()).copy();
            session.playerDimension = player.level().dimension().location().toString();
            require(player.serverLevel() == level, "Reference player must be in the platform world");
            session.dayTime = level.getDayTime();
            session.mobSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            session.daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            session.weatherCycle = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
            var data = (ServerLevelData) level.getLevelData();
            session.clearWeather = data.getClearWeatherTime();
            session.rainTime = data.getRainTime();
            session.thunderTime = data.getThunderTime();
            session.raining = data.isRaining();
            session.thundering = data.isThundering();
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                var pos = new BlockPos(x, 64, z);
                require(level.getBlockEntity(pos) == null, "Unknown original platform tile must remain intact");
                session.floor.put(pos, level.getBlockState(pos));
            }
        }).join();
        active = session;
        var spawn = new JsonObject();
        spawn.addProperty("model", INITIAL_MODEL);
        try {
            var response = ReferenceScene.spawn(spawn, session.entity, () -> {});
            response.addProperty("audioRun", session.entity.toString());
            response.addProperty("scope", "Original constructor/default and native GUI/playback chain; NoAI fixture, not natural trigger frequency");
            return response;
        } catch (RuntimeException failure) {
            synchronized (session) { session.failures.add(failure.toString()); }
            throw failure;
        }
    }

    /** Called exactly after the existing new EntityMaid, before fixture UUID/model/owner writes. */
    public static void constructed(EntityMaid maid) {
        var session = active;
        if (session == null) return;
        try {
            var row = new JsonObject();
            row.addProperty("modelId", maid.getModelId());
            row.addProperty("soundPackId", maid.getSoundPackId());
            row.addProperty("soundFrequency", maid.getConfigManager().getSoundFreq());
            row.addProperty("uuidBeforeFixtureIdentity", maid.getUUID().toString());
            row.addProperty("point", "immediately-after-original-constructor-before-fixture-writes");
            synchronized (session) {
                require(session.constructor == null, "Constructor observer was called twice for this fixture");
                session.constructor = row;
            }
        } catch (Exception | LinkageError failure) {
            synchronized (session) { session.failures.add("constructor observation: " + failure); }
        }
    }

    /** Original successful decode callback; no re-decode, selection, sound queue or buffer-position change. */
    public static void loaded(SoundData data, String source) {
        try {
            synchronized (LOADED) {
                LOADED.removeIf(entry -> entry.buffer().get() == null);
                LOADED.add(new Loaded(new WeakReference<>(data.byteBuffer()), source));
            }
        } catch (Exception | LinkageError failure) {
            synchronized (LOADED) { if (LOAD_FAILURES.size() < 16) LOAD_FAILURES.add(failure.toString()); }
        }
    }

    private static JsonArray sourcePaths(ByteBuffer buffer) {
        var paths = new JsonArray();
        synchronized (LOADED) {
            for (var loaded : LOADED) if (loaded.buffer().get() == buffer) paths.add(loaded.source());
        }
        return paths;
    }

    /** Read the original local already selected by getSoundBuffer; never call that RNG-consuming getter here. */
    public static void selected(MaidSoundInstance instance, SoundBuffer selected) {
        var session = active;
        if (session == null || !instance.getMaid().getUUID().equals(session.entity)) return;
        try {
            var row = new JsonObject();
            synchronized (session) {
                row.addProperty("phase", session.requestedPhases.getOrDefault(instance, "incidental/" + session.phase));
            }
            row.addProperty("modelId", instance.getMaid().getModelId());
            row.addProperty("actualMaidSoundPackId", instance.getMaid().getSoundPackId());
            row.addProperty("requestedPack", instance.getId());
            row.addProperty("event", instance.getLocation().toString());
            row.addProperty("testSound", instance.isTestSound());
            row.addProperty("instancePitch", instance.getPitch());
            row.addProperty("instanceVolume", instance.getVolume());
            row.addProperty("thread", Thread.currentThread().getName());
            if (selected == null) {
                row.addProperty("selectedBufferStatus", "NO_BUFFER");
            } else {
                if (pcmField == null) pcmField = field(SoundBuffer.class, "data");
                if (formatField == null) formatField = field(SoundBuffer.class, "format");
                var pcm = (ByteBuffer) pcmField.get(selected);
                var format = (AudioFormat) formatField.get(selected);
                require(pcm != null && format != null, "Selected buffer PCM/format unavailable before attachment");
                row.addProperty("selectedBufferStatus", "ACTUAL_LOCAL_BUFFER_BEFORE_ATTACHMENT");
                row.add("pcm", pcm(pcm, format));
                row.add("selectedOriginalSourcePaths", sourcePaths(pcm));
                var matches = new JsonArray();
                var cache = CustomSoundLoader.CACHE.get(instance.getId());
                if (cache != null) {
                    var candidates = cache.buffers().get(instance.getLocation());
                    if (candidates != null) for (int n = 0; n < candidates.size(); n++) {
                        var candidate = candidates.get(n);
                        if (candidate.byteBuffer() == pcm) matches.add(n);
                    }
                }
                row.add("actualCacheBufferIdentityIndices", matches);
            }
            synchronized (session) {
                if (session.samples.size() >= 32) {
                    session.failures.add("Observation sample cap reached; later samples not represented");
                    return;
                }
                session.samples.add(row);
                session.pending.put(instance, row);
                session.sounds.add(instance);
            }
        } catch (Exception | LinkageError failure) {
            synchronized (session) { session.failures.add("selected-buffer observation: " + failure); }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void played(PlaySoundSourceEvent event) {
        var session = active;
        if (session == null || !(event.getSound() instanceof MaidSoundInstance instance)
            || !instance.getMaid().getUUID().equals(session.entity)) return;
        synchronized (session) {
            var row = session.pending.remove(instance);
            if (row == null) return;
            try {
                if (channelSourceField == null) channelSourceField = field(event.getChannel().getClass(), "source");
                int source = channelSourceField.getInt(event.getChannel());
                int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
                row.addProperty("alStatus", "READ_AFTER_ORIGINAL_BUFFER_REPLACEMENT");
                row.addProperty("alSource", source);
                row.addProperty("alBuffer", buffer);
                row.addProperty("alPitch", AL10.alGetSourcef(source, AL10.AL_PITCH));
                row.addProperty("alGain", AL10.alGetSourcef(source, AL10.AL_GAIN));
                row.addProperty("alState", AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE));
                row.addProperty("alFrequency", AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY));
                row.addProperty("alChannels", AL10.alGetBufferi(buffer, AL10.AL_CHANNELS));
                row.addProperty("alBits", AL10.alGetBufferi(buffer, AL10.AL_BITS));
                row.addProperty("alBytes", AL10.alGetBufferi(buffer, AL10.AL_SIZE));
                row.addProperty("channelPlaying", event.getChannel().playing());
                row.addProperty("observedThread", Thread.currentThread().getName());
            } catch (Exception | LinkageError failure) {
                row.addProperty("alStatus", "UNAVAILABLE");
                row.addProperty("alReadFailure", failure.toString());
            }
        }
    }

    private static JsonObject play(Session session) {
        var mc = Minecraft.getInstance();
        var maid = maid(session);
        require(maid.getSoundPackId().equals(serverSnapshot(session).get("soundPackId").getAsString()),
            "Wait for actual client/server sound selection before playback");
        if (session.phase.startsWith("after"))
            require(TARGET_MODEL.equals(maid.getModelId())
                && TARGET_MODEL.equals(serverSnapshot(session).get("modelId").getAsString()),
                "Wait for the actual server model change and client sync before after-playback");
        var sound = new MaidSoundInstance(InitSounds.MAID_IDLE.get(), maid.getSoundPackId(), maid, true);
        synchronized (session) { session.requestedPhases.put(sound, session.phase); }
        // Same original constructor and playback entry as its GUI preview; default pack and frequency values stay intact.
        mc.getSoundManager().play(sound);
        var result = status(session);
        result.addProperty("playRequested", true);
        result.addProperty("controlledEvent", InitSounds.MAID_IDLE.get().getLocation().toString());
        result.addProperty("controlledScope", "GUI-preview-equivalent native event, testSound=true; not natural frequency evidence");
        return result;
    }

    private static JsonObject selectMagic(Session session) {
        var mc = Minecraft.getInstance();
        require(mc.screen instanceof MaidModelGui && !Screen.hasShiftDown(), "Actual original model screen without Shift required");
        synchronized (session) { session.beforeSelection = modelSnapshot(maid(session)); }
        int index = -1;
        var packs = CustomPackLoader.MAID_MODELS.getPackList();
        for (int n = 0; n < packs.size(); n++) if (packs.get(n).getId().equals("geckolib")) index = n;
        require(index >= 0 && index < 7, "Actual geckolib tab must be visible in the original GUI");
        var selectPack = new JsonObject();
        selectPack.addProperty("pack", index);
        GuiReferenceProbe.modelsPack(selectPack);
        var screen = (MaidModelGui) mc.screen;
        try {
            var getModels = AbstractModelGui.class.getDeclaredMethod("getDisplayModelList");
            getModels.setAccessible(true);
            var models = (List<?>) getModels.invoke(screen);
            int row = (Integer) field(MaidModelGui.class, "ROW_INDEX").get(null);
            int first = row * 11;
            int selected = -1;
            for (int n = first; n < Math.min(models.size(), first + 55); n++)
                if (((IModelInfo) models.get(n)).getModelId().toString().equals(TARGET_MODEL)) selected = n - first;
            require(selected >= 0, "Magic Winefox must be an actual visible model button");
            double x = screen.width / 2.0 + 50 - 101 + 20 * (selected % 11);
            double y = screen.height / 2.0 - 49 + 30 * (selected / 11);
            require(screen.mouseClicked(x, y, 0), "Original model button did not accept the click");
            screen.mouseReleased(x, y, 0);
            session.phase = "after-model-button-request";
            return status(session);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot observe the original visible model button", failure);
        }
    }

    private static JsonObject status(Session session) {
        var result = new JsonObject();
        result.addProperty("schema", "original-audio-route-observation-v1");
        result.addProperty("run", session.entity.toString());
        result.addProperty("scope", "One original default maid, actual GUI model selection and native buffer/AL playback; NoAI and controlled preview are explicit");
        result.addProperty("originalAudioOrDefaultChanged", false);
        result.addProperty("naturalTriggerFrequencyAccepted", false);
        result.addProperty("actualCrossEngineAudioMatchAccepted", false);
        result.addProperty("sceneStatus", ReferenceScene.status().toString());
        var packs = new JsonArray();
        Minecraft.getInstance().getResourcePackRepository().getSelectedIds().forEach(packs::add);
        result.add("selectedResourcePacks", packs);
        result.addProperty("resourcePacksUnchanged", session.resourcePacks.equals(List.copyOf(Minecraft.getInstance().getResourcePackRepository().getSelectedIds())));
        synchronized (session) {
            result.add("constructor", session.constructor == null ? null : session.constructor.deepCopy());
            result.add("beforeModelSelection", session.beforeSelection == null ? null : session.beforeSelection.deepCopy());
            result.add("playbackSamples", session.samples.deepCopy());
            result.add("observerFailures", session.failures.deepCopy());
        }
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(ReferenceScene.status().get("entityId").getAsInt()) instanceof EntityMaid maid
            && maid.getUUID().equals(session.entity)) result.add("client", modelSnapshot(maid));
        result.add("server", serverSnapshot(session));
        if (session.beforePools == null && result.has("client")) {
            session.beforePools = pools();
            session.loadedModels = models();
        }
        if (session.afterPools == null && result.has("client")
            && TARGET_MODEL.equals(result.getAsJsonObject("client").get("modelId").getAsString())
            && TARGET_MODEL.equals(result.getAsJsonObject("server").get("modelId").getAsString()))
            session.afterPools = pools();
        result.add("allRuntimeModelSoundMappings", session.loadedModels == null ? null : session.loadedModels.deepCopy());
        result.add("completePoolsBeforeModelSelection", session.beforePools == null ? null : session.beforePools.deepCopy());
        result.add("completePoolsAfterModelSelection", session.afterPools == null ? null : session.afterPools.deepCopy());
        synchronized (LOADED) { result.add("loadObserverFailures", LOAD_FAILURES.deepCopy()); }
        result.add("runtimeIdentity", ReferenceRuntimeIdentity.capture().runtimeIdentity());
        return result;
    }

    private static JsonObject modelSnapshot(EntityMaid maid) {
        var result = new JsonObject();
        result.addProperty("entityUuid", maid.getUUID().toString());
        result.addProperty("modelId", maid.getModelId());
        result.addProperty("soundPackId", maid.getSoundPackId());
        result.addProperty("soundFrequency", maid.getConfigManager().getSoundFreq());
        result.addProperty("globalSoundPercent", com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig.GLOBAL_MAID_SOUND_FREQUENCY.get());
        result.addProperty("noAi", maid.isNoAi());
        var info = CustomPackLoader.MAID_MODELS.getInfo(maid.getModelId());
        result.addProperty("modelInfoPresent", info.isPresent());
        if (info.isPresent()) {
            var actual = info.get();
            result.addProperty("runtimeUseSoundPackId", actual.getUseSoundPackId());
            result.add("runtimeModelInfo", new GsonBuilder().serializeNulls().create().toJsonTree(actual));
        }
        var cache = CustomSoundLoader.CACHE.get(maid.getSoundPackId());
        result.addProperty("actualSoundCachePresent", cache != null);
        if (cache != null) {
            result.add("actualSoundPackInfo", new GsonBuilder().serializeNulls().create().toJsonTree(cache.info()));
            var events = new JsonObject();
            cache.buffers().forEach((event, buffers) -> events.addProperty(event.toString(), buffers.size()));
            result.add("actualSoundCacheEventCounts", events);
        }
        return result;
    }

    private static JsonArray models() {
        var result = new JsonArray();
        for (var pack : CustomPackLoader.MAID_MODELS.getPackList()) for (var info : pack.getModelList()) {
            var row = new JsonObject();
            row.addProperty("modelId", info.getModelId().toString());
            row.addProperty("packId", pack.getId());
            row.addProperty("runtimeUseSoundPackId", info.getUseSoundPackId());
            result.add(row);
        }
        return result;
    }

    private static JsonObject pools() {
        var result = new JsonObject();
        var pcmByIdentity = new IdentityHashMap<ByteBuffer, JsonObject>();
        CustomSoundLoader.CACHE.forEach((id, cache) -> {
            var events = new JsonObject();
            cache.buffers().forEach((event, buffers) -> {
                var rows = new JsonArray();
                for (int n = 0; n < buffers.size(); n++) {
                    var data = buffers.get(n);
                    var row = new JsonObject();
                    row.addProperty("index", n);
                    row.add("originalSourcePaths", sourcePaths(data.byteBuffer()));
                    try {
                        var observed = pcmByIdentity.get(data.byteBuffer());
                        if (observed == null) {
                            observed = pcm(data.byteBuffer(), data.audioFormat());
                            pcmByIdentity.put(data.byteBuffer(), observed);
                        }
                        row.add("pcm", observed.deepCopy());
                    } catch (Exception failure) {
                        row.addProperty("pcmStatus", "UNAVAILABLE");
                        row.addProperty("failure", failure.toString());
                    }
                    rows.add(row);
                }
                events.add(event.toString(), rows);
            });
            result.add(id, events);
        });
        return result;
    }

    private static JsonObject serverSnapshot(Session session) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        require(server != null, "Original integrated server is unavailable");
        return server.submit(() -> {
            var entity = server.overworld().getEntity(session.entity);
            var result = new JsonObject();
            result.addProperty("registered", entity instanceof EntityMaid && !entity.isRemoved());
            if (entity instanceof EntityMaid maid) {
                result.addProperty("entityUuid", maid.getUUID().toString());
                result.addProperty("modelId", maid.getModelId());
                result.addProperty("soundPackId", maid.getSoundPackId());
                result.addProperty("soundFrequency", maid.getConfigManager().getSoundFreq());
                result.addProperty("noAi", maid.isNoAi());
            }
            return result;
        }).join();
    }

    private static JsonObject restore(Session session) {
        var mc = Minecraft.getInstance();
        var result = status(session);
        require(session.resourcePacks.equals(List.copyOf(mc.getResourcePackRepository().getSelectedIds())), "Unknown resource-pack successor retained");
        if (mc.screen != null) {
            GuiReferenceProbe.status();
            mc.player.closeContainer();
            mc.setScreen(null);
        }
        synchronized (session) { for (var sound : session.sounds) mc.getSoundManager().stop(sound); }
        var restored = mc.getSingleplayerServer().submit(() -> {
            var server = mc.getSingleplayerServer();
            var level = server.overworld();
            var entity = level.getEntity(session.entity);
            require(entity instanceof EntityMaid maid && ReferenceScene.isCurrent(maid), "Unknown actor successor retained");
            var player = server.getPlayerList().getPlayer(session.owner);
            require(player != null && player.serverLevel() == level && player.isSpectator()
                && player.getInventory().save(new ListTag()).equals(session.playerInventory), "Unknown player/inventory successor retained");
            for (var pos : session.floor.keySet()) {
                var expected = ((pos.getX() + pos.getZ()) & 1) == 0 ? Blocks.GRAY_WOOL.defaultBlockState() : Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
                require(level.getBlockEntity(pos) == null && level.getBlockState(pos).equals(expected), "Unknown platform successor retained");
            }
            entity.discard();
            for (var entry : session.floor.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(session.mobSpawning, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(session.daylight, server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(session.weatherCycle, server);
            level.setDayTime(session.dayTime);
            var data = (ServerLevelData) level.getLevelData();
            data.setClearWeatherTime(session.clearWeather);
            data.setRainTime(session.rainTime);
            data.setThunderTime(session.thunderTime);
            data.setRaining(session.raining);
            data.setThundering(session.thundering);
            player.teleportTo(level, session.playerPosition.x, session.playerPosition.y, session.playerPosition.z, session.yaw, session.pitch);
            player.setDeltaMovement(session.playerVelocity);
            for (var entry : session.floor.entrySet()) require(level.getBlockState(entry.getKey()).equals(entry.getValue()), "Platform restoration readback differs");
            var facts = new JsonObject();
            facts.addProperty("ownedActorRemoved", level.getEntity(session.entity) == null || level.getEntity(session.entity).isRemoved());
            facts.addProperty("floor441StatesRestored", true);
            facts.addProperty("playerInventoryExact", player.getInventory().save(new ListTag()).equals(session.playerInventory));
            facts.addProperty("playerPositionRotationRestored", player.position().equals(session.playerPosition) && player.getYRot() == session.yaw && player.getXRot() == session.pitch);
            facts.addProperty("onlyOwnObservedSoundsStopped", true);
            return facts;
        }).join();
        result.add("restoration", restored);
        try {
            Files.createDirectories(session.root.resolve("captures"));
            Path output = session.root.resolve("captures/audio-" + session.entity + ".json");
            Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result) + "\n", StandardOpenOption.CREATE_NEW);
            result.addProperty("capture", "captures/" + output.getFileName());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Audio evidence save failed; original samples remain in status responses", failure);
        }
        active = null;
        return result;
    }

    private static EntityMaid maid(Session session) {
        var mc = Minecraft.getInstance();
        var scene = ReferenceScene.status();
        require(mc.level != null && "ready".equals(scene.get("status").getAsString()), "Wait for the original reference scene");
        var entity = mc.level.getEntity(scene.get("entityId").getAsInt());
        require(entity instanceof EntityMaid && entity.getUUID().equals(session.entity), "Original client maid is unavailable");
        return (EntityMaid) entity;
    }

    private static JsonObject pcm(ByteBuffer input, AudioFormat format) throws Exception {
        var view = input.duplicate();
        int bytes = view.remaining();
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(view);
        var row = new JsonObject();
        row.addProperty("sha256", HexFormat.of().formatHex(digest.digest()));
        row.addProperty("bytes", bytes);
        row.addProperty("sourcePosition", input.position());
        row.addProperty("sourceLimit", input.limit());
        row.addProperty("sampleRate", format.getSampleRate());
        row.addProperty("channels", format.getChannels());
        row.addProperty("sampleSizeBits", format.getSampleSizeInBits());
        row.addProperty("frameSize", format.getFrameSize());
        row.addProperty("frameRate", format.getFrameRate());
        row.addProperty("bigEndian", format.isBigEndian());
        row.addProperty("encoding", format.getEncoding().toString());
        return row;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Session requireSession() {
        var session = active;
        require(session != null, "Prepare the bounded audio-reference fixture first");
        return session;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Loaded(WeakReference<ByteBuffer> buffer, String source) {}

    private static final class Session {
        final Path root;
        final UUID entity, owner;
        final JsonArray samples = new JsonArray(), failures = new JsonArray();
        final Map<MaidSoundInstance, JsonObject> pending = new IdentityHashMap<>();
        final Map<MaidSoundInstance, String> requestedPhases = new IdentityHashMap<>();
        final List<MaidSoundInstance> sounds = new ArrayList<>();
        final Map<BlockPos, BlockState> floor = new LinkedHashMap<>();
        volatile String phase = "before-model-selection";
        JsonObject constructor, beforeSelection, beforePools, afterPools;
        JsonArray loadedModels;
        List<String> resourcePacks;
        ListTag playerInventory;
        Vec3 playerPosition, playerVelocity;
        String playerDimension;
        float yaw, pitch;
        long dayTime;
        int clearWeather, rainTime, thunderTime;
        boolean mobSpawning, daylight, weatherCycle, raining, thundering;
        Session(Path root, UUID entity, UUID owner) {
            this.root = root;
            this.entity = entity;
            this.owner = owner;
        }
    }
}
