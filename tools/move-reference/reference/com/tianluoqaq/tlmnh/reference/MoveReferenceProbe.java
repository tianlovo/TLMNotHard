package com.tianluoqaq.tlmnh.reference;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidPathNavigation;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 原MoveToTargetSink/Behavior真实执行，导航与时钟依赖受控，不作为自然Brain成绩 */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class MoveReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static volatile boolean pending;
    private static boolean failed;

    private MoveReferenceProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.moveProbe") || event.phase != TickEvent.Phase.END || failed || pending) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-move-reference");
                for (String child : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(child));
                TouhouLittleMaid.LOGGER.info("TLMNH_MOVE_REFERENCE_READY contract=MOVE_SINK_CORE_V1");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var requests = new ArrayList<Path>();
                stream.forEach(requests::add);
                requests.sort(Path::compareTo);
                if (!requests.isEmpty()) handle(requests.get(0));
            }
        } catch (Exception | LinkageError failure) {
            failed = true;
            TouhouLittleMaid.LOGGER.error("TLMNH_MOVE_REFERENCE_IO_FAILED type={}", failure.getClass().getSimpleName());
        }
    }

    private static void handle(Path path) throws Exception {
        String name = path.getFileName().toString();
        var response = new JsonObject();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= 16384, "Invalid move request");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> {
                    response.addProperty("contract", "MOVE_SINK_CORE_V1");
                    response.addProperty("worldLoaded", minecraft.level != null && minecraft.getSingleplayerServer() != null);
                    response.addProperty("spectator", minecraft.player != null && minecraft.player.isSpectator());
                    response.addProperty("pending", pending);
                }
                case "lab_create" -> response = ReferenceScene.createLab();
                case "lab_open" -> response = ReferenceScene.openLab();
                case "capture_move" -> {
                    require(request.get("repetitions").getAsInt() == 3 && request.get("repetitions").getAsDouble() == 3,
                        "Exactly three fresh entity/state builds required");
                    var server = minecraft.getSingleplayerServer();
                    require(server != null && minecraft.level != null && minecraft.player != null
                        && minecraft.player.isSpectator() && minecraft.player.getAbilities().invulnerable,
                        "Protected integrated reference spectator required");
                    require(server.getWorldData().getLevelName().equals("TLMNH-Reference-Lab"), "Dedicated reference lab required");
                    minecraft.options.pauseOnLostFocus = false;
                    minecraft.setScreen(null);
                    pending = true;
                    Files.delete(path);
                    server.execute(() -> executeCapture(server, name));
                    return;
                }
                case "shutdown" -> minecraft.stop();
                default -> throw new IllegalArgumentException("Unsupported move command");
            }
            response.addProperty("status", "complete");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive");
            response.addProperty("errorType", failure.getClass().getSimpleName());
            response.addProperty("error", safeMessage(failure));
        }
        response.addProperty("request", name);
        write(root.resolve("responses").resolve(name), response);
        Files.deleteIfExists(path);
    }

    private static void executeCapture(MinecraftServer server, String name) {
        var response = new JsonObject();
        try {
            require(server.isSameThread(), "Actual server thread required");
            var report = capture(server);
            Path destination = root.resolve("captures").resolve(name);
            write(destination, report);
            response.addProperty("capture", "captures/" + name);
            response.addProperty("captureSha256", sha(Files.readAllBytes(destination)));
            response.addProperty("status", report.get("complete").getAsBoolean() ? "complete" : "inconclusive");
            response.addProperty("verdict", report.get("complete").getAsBoolean() ? "CAPTURED_NOT_COMPARED" : "COMPONENT_CASE_FAILED");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive");
            response.addProperty("errorType", failure.getClass().getSimpleName());
            response.addProperty("error", safeMessage(failure));
        } finally {
            response.addProperty("request", name);
            try { write(root.resolve("responses").resolve(name), response); }
            catch (Exception failure) { failed = true; }
            pending = false;
        }
    }

    private static JsonObject capture(MinecraftServer server) throws Exception {
        require(server.isSameThread(), "Actual server thread required");
        var mods = Set.copyOf(ModList.get().getMods().stream().map(info -> info.getModId()).toList());
        require(mods.equals(Set.of("minecraft", "forge", "touhou_little_maid")), "Minimal original reference mods required");
        require(ModList.get().getModContainerById("forge").orElseThrow().getModInfo().getVersion().toString().equals("47.2.0"),
            "Forge 47.2.0 required");
        var contract = JsonParser.parseString(new String(MoveReferenceIdentity.resource("contract.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        require(contract.get("schema").getAsString().equals("move-sink-contract-v1")
            && contract.get("contract").getAsString().equals("MOVE_SINK_CORE_V1") && contract.get("rebuilds").getAsInt() == 3,
            "Unsupported move contract");
        var report = new JsonObject();
        report.addProperty("schema", "move-sink-reference-v1");
        report.addProperty("inputObservationSchema", "move-sink-input-observations-v1");
        report.addProperty("contract", "MOVE_SINK_CORE_V1");
        report.addProperty("contractSha256", MoveReferenceIdentity.sha(MoveReferenceIdentity.resource("contract.json")));
        report.addProperty("scope", contract.get("scope").getAsString());
        report.addProperty("naturalBrainVerified", false);
        report.addProperty("nativeNavigationVerified", false);
        report.addProperty("originalMethods", "MoveToTargetSink + inherited Behavior.tryStart/tickOrStop/doStop");
        report.addProperty("worldRandomSource", "controlled bounded-integer feed; requests and supplied results observed");
        report.addProperty("maidRandomSource", "actual RandomSource.create(seed) delegate; original fallback consumes it");
        report.addProperty("fallbackObservationScope", "original DefaultRandomPos executes; only final coordinate-wrapper block query is observed, not a direct Vec3 return hook");
        report.add("identity", MoveReferenceIdentity.capture());
        report.addProperty("complete", true);
        var rows = new JsonArray();
        report.add("cases", rows);
        prepareAir(server.overworld());
        long started = System.nanoTime();
        require(contract.getAsJsonArray("cases").size() <= 64, "Move case capacity exceeded");
        outer:
        for (var value : contract.getAsJsonArray("cases")) {
            var spec = value.getAsJsonObject();
            for (int rebuild = 0; rebuild < 3; rebuild++) {
                try {
                    require(System.nanoTime() - started < 120_000_000_000L, "Move capture deadline exceeded");
                    var row = runCase(server, spec, rebuild);
                    rows.add(row);
                    if (!row.get("complete").getAsBoolean()) {
                        report.addProperty("complete", false);
                        break outer;
                    }
                } catch (Exception | LinkageError failure) {
                    report.add("failure", failure(spec.get("id").getAsString(), rebuild, failure));
                    report.addProperty("complete", false);
                    break outer;
                }
            }
        }
        return report;
    }

    private static void prepareAir(ServerLevel level) {
        for (int x : new int[] {-16, 0, 16}) for (int z : new int[] {-16, 0, 16}) level.getChunk(x >> 4, z >> 4);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = 182; y <= 202; y++) {
            var at = new BlockPos(x, y, z);
            require(level.isEmptyBlock(at) && level.getBlockEntity(at) == null, "Fallback read region must be pure loaded air");
        }
    }

    private static JsonObject runCase(MinecraftServer server, JsonObject spec, int rebuild) throws Exception {
        var level = server.overworld();
        require(level.getLevelData() instanceof ServerLevelData, "Mutable original server level clock required");
        var data = (ServerLevelData) level.getLevelData();
        long oldTime = level.getGameTime();
        Field randomField = Level.class.getDeclaredField("random");
        randomField.setAccessible(true);
        RandomSource oldRandom = level.getRandom();
        var world = new RandomFeed(4408L, true, spec.getAsJsonArray("worldInts"));
        Fixture fixture = null;
        try {
            randomField.set(level, world);
            require(level.getRandom() == world, "World RNG adapter unavailable");
            fixture = new Fixture(server, level, spec, world);
            var row = new JsonObject();
            row.addProperty("id", spec.get("id").getAsString());
            row.addProperty("rebuild", rebuild);
            row.addProperty("complete", true);
            row.add("input", copy(spec));
            row.add("paths", fixture.actualPaths());
            row.add("initialState", fixture.snapshot());
            row.add("initialInputObservation", fixture.inputObservation());
            row.add("initialMaidBlock", block(fixture.maid.blockPosition()));
            row.add("initialTargetBlock", block(fixture.target.blockPosition()));
            row.addProperty("initialTargetSpectator", fixture.target.isSpectator());
            var steps = new JsonArray();
            row.add("steps", steps);
            require(spec.getAsJsonArray("steps").size() <= 32, "Move step capacity exceeded");
            int index = 0;
            for (var step : spec.getAsJsonArray("steps")) {
                try {
                    steps.add(fixture.runStep(step.getAsJsonObject(), index, data));
                } catch (Exception | LinkageError failure) {
                    var problem = failure(spec.get("id").getAsString(), rebuild, failure);
                    problem.addProperty("index", index);
                    row.add("failure", problem);
                    row.addProperty("complete", false);
                    break;
                }
                index++;
            }
            return row;
        } finally {
            try { if (fixture != null) fixture.close(); }
            finally {
                try { data.setGameTime(oldTime); }
                finally {
                    randomField.set(level, oldRandom);
                    require(level.getRandom() == oldRandom && level.getGameTime() == oldTime, "Clock or RNG restoration failed");
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final ProbeMaid maid;
        final Target target;
        final MoveToTargetSink sink = new MoveToTargetSink();
        final ControlledNavigation navigation;
        final RandomFeed world, entityRandom;
        final JsonObject config;
        final Map<String, net.minecraft.world.level.pathfinder.Path> paths = new HashMap<>();
        final Map<net.minecraft.world.level.pathfinder.Path, String> pathIds = new IdentityHashMap<>();
        boolean recording, fallbackSeen;
        JsonArray calls = new JsonArray(), moveObservations = new JsonArray(), callbackObservations = new JsonArray();
        JsonArray stabilityChecks = new JsonArray(), scores = new JsonArray(), malusChecks = new JsonArray();
        BlockPos fallbackQuery, onCreateMaidBlock;
        int createIndex;

        Fixture(MinecraftServer server, ServerLevel level, JsonObject spec, RandomFeed world) throws Exception {
            this.level = level;
            this.world = world;
            config = copy(spec.getAsJsonObject("initial"));
            target = new Target(server, level, new GameProfile(stableUuid(spec, "target"), "TLMNHMoveTarget"));
            maid = new ProbeMaid(level);
            maid.setUUID(stableUuid(spec, "maid"));
            maid.setNoAi(true);
            maid.setHomeModeEnable(false);
            navigation = new ControlledNavigation(maid, this);
            maid.fixture = this;
            world.fixture = this;
            entityRandom = new RandomFeed(spec.get("maidSeed").getAsLong(), false, new JsonArray());
            entityRandom.fixture = this;
            for (var entry : spec.getAsJsonObject("paths").entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                var nodes = new ArrayList<Node>();
                for (var item : value.getAsJsonArray("nodes")) {
                    var at = parseBlock(item); nodes.add(new Node(at.getX(), at.getY(), at.getZ()));
                }
                var path = new net.minecraft.world.level.pathfinder.Path(nodes, parseBlock(value.get("target")), value.get("canReach").getAsBoolean());
                paths.put(entry.getKey(), path);
                pathIds.put(path, entry.getKey());
            }
            applyPatch(config, false);
        }

        JsonObject runStep(JsonObject step, int index, ServerLevelData data) throws Exception {
            require(!recording, "Reentrant component step");
            JsonObject patch = step.getAsJsonObject("patch");
            applyPatch(patch, true);
            data.setGameTime(step.get("worldGameTime").getAsLong());
            require(level.getGameTime() == step.get("worldGameTime").getAsLong(), "Controlled world clock unavailable");
            calls = new JsonArray(); moveObservations = new JsonArray(); callbackObservations = new JsonArray();
            stabilityChecks = new JsonArray(); scores = new JsonArray(); malusChecks = new JsonArray();
            world.calls = new JsonArray(); entityRandom.calls = new JsonArray();
            createIndex = 0; fallbackSeen = false; fallbackQuery = null;
            var result = new JsonObject();
            result.addProperty("index", index);
            result.addProperty("operation", step.get("operation").getAsString());
            result.addProperty("methodTime", step.get("methodTime").getAsLong());
            result.addProperty("worldGameTime", level.getGameTime());
            var input = new JsonObject(); input.add("externalPatch", copy(patch)); result.add("input", input);
            result.add("before", snapshot());
            Boolean returned = null;
            recording = true;
            try {
                returned = invokeOriginal(result, step.get("operation").getAsString(), level, maid,
                    step.get("methodTime").getAsLong());
            } finally { recording = false; }
            result.addProperty("methodReturn", returned);
            result.add("after", snapshot());
            result.add("callTrace", frozen(calls));
            result.add("moveObservations", frozen(moveObservations));
            result.add("callbackObservations", frozen(callbackObservations));
            result.add("worldRandomCalls", frozen(world.calls));
            result.add("maidRandomCalls", frozen(entityRandom.calls));
            var fallback = new JsonObject();
            fallback.addProperty("invoked", fallbackSeen);
            fallback.add("queryBlock", fallbackQuery == null ? JsonNull.INSTANCE : block(fallbackQuery));
            fallback.addProperty("directVec3Observed", false);
            fallback.add("stabilityChecks", frozen(stabilityChecks));
            fallback.add("scores", frozen(scores));
            fallback.add("malusChecks", frozen(malusChecks));
            result.add("fallbackObservation", fallback);
            return result;
        }

        /** Records the arguments at the sole actual invocation, rather than copying the planned input as evidence */
        private Boolean invokeOriginal(JsonObject result, String operation, ServerLevel actualLevel,
                                       ProbeMaid actualMaid, long actualMethodTime) {
            result.add("inputBeforeInvocation", inputObservation());
            var invocation = new JsonObject();
            invocation.addProperty("operation", operation);
            invocation.addProperty("methodTime", actualMethodTime);
            invocation.addProperty("worldGameTime", actualLevel.getGameTime());
            invocation.addProperty("entityIsFixtureMaid", actualMaid == maid);
            invocation.addProperty("worldIsFixtureLevel", actualLevel == level);
            result.add("methodInvocation", invocation);
            try {
                return switch (operation) {
                    case "tryStart" -> sink.tryStart(actualLevel, actualMaid, actualMethodTime);
                    case "tickOrStop" -> { sink.tickOrStop(actualLevel, actualMaid, actualMethodTime); yield null; }
                    case "doStop" -> { sink.doStop(actualLevel, actualMaid, actualMethodTime); yield null; }
                    default -> throw new IllegalArgumentException("Unsupported original sink operation");
                };
            } finally {
                result.add("inputAfterInvocation", inputObservation());
            }
        }

        JsonObject inputObservation() {
            var result = new JsonObject();
            result.add("maidBlock", block(maid.blockPosition()));
            result.add("maidPosition", position(maid.position()));
            result.add("targetEntityBlock", block(target.blockPosition()));
            result.add("targetEntityPosition", position(target.position()));
            result.addProperty("targetSpectator", target.isSpectator());
            result.addProperty("worldGameTime", level.getGameTime());
            WalkTarget walk = registered(MemoryModuleType.WALK_TARGET)
                ? maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) : null;
            if (walk == null) result.add("walkTracker", JsonNull.INSTANCE);
            else {
                var tracker = walk.getTarget();
                var value = new JsonObject();
                value.addProperty("type", tracker instanceof EntityTracker ? "entity"
                    : tracker instanceof BlockPosTracker ? "block" : "other");
                value.add("block", block(tracker.currentBlockPosition()));
                value.add("currentPosition", position(tracker.currentPosition()));
                if (tracker instanceof EntityTracker entityTracker) {
                    var entity = entityTracker.getEntity();
                    value.addProperty("entityIsConfiguredTarget", entity == target);
                    value.addProperty("entityUuid", entity.getUUID().toString());
                    value.addProperty("entityRuntimeClass", entity.getClass().getName());
                    value.addProperty("entitySpectator", entity.isSpectator());
                } else {
                    value.add("entityIsConfiguredTarget", JsonNull.INSTANCE);
                    value.add("entityUuid", JsonNull.INSTANCE);
                    value.add("entityRuntimeClass", JsonNull.INSTANCE);
                    value.add("entitySpectator", JsonNull.INSTANCE);
                }
                result.add("walkTracker", value);
            }
            return result;
        }

        void applyPatch(JsonObject patch, boolean merge) throws Exception {
            if (merge) merge(config, patch);
            if (patch.has("maidBlock")) setBlockPosition(maid, parseBlock(config.get("maidBlock")));
            if (patch.has("targetBlock")) setBlockPosition(target, parseBlock(config.get("targetBlock")));
            if (patch.has("targetSpectator")) target.spectator = config.get("targetSpectator").getAsBoolean();
            if (patch.has("registered")) {
                var flags = config.getAsJsonObject("registered");
                registration(MemoryModuleType.WALK_TARGET, flags.get("walk").getAsBoolean());
                registration(MemoryModuleType.PATH, flags.get("path").getAsBoolean());
                registration(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, flags.get("cantReachSince").getAsBoolean());
            }
            if (patch.has("walk")) {
                var value = config.getAsJsonObject("walk");
                if (!value.get("present").getAsBoolean()) maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                else {
                    WalkTarget walk = value.get("tracker").getAsString().equals("entity")
                        ? new WalkTarget(new EntityTracker(target, true), value.get("speed").getAsFloat(), value.get("closeEnough").getAsInt())
                        : new WalkTarget(target.blockPosition(), value.get("speed").getAsFloat(), value.get("closeEnough").getAsInt());
                    maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walk);
                }
            }
            if (patch.has("pathMemory")) maid.getBrain().setMemory(MemoryModuleType.PATH, path(config.get("pathMemory")));
            if (patch.has("cantReachSince")) {
                if (config.get("cantReachSince").isJsonNull()) maid.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                else maid.getBrain().setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, config.get("cantReachSince").getAsLong());
            }
            if (patch.has("navigation")) {
                var actual = patch.getAsJsonObject("navigation");
                var values = config.getAsJsonObject("navigation");
                if (actual.has("path")) navigation.current = path(values.get("path"));
                if (actual.has("done")) navigation.done = values.get("done").getAsBoolean();
                if (actual.has("stuck")) navigation.stuck = values.get("stuck").getAsBoolean();
                if (actual.has("moveResult")) navigation.moveResult = values.get("moveResult").getAsBoolean();
            }
            if (patch.has("createReplies")) {
                navigation.replies.clear(); navigation.replyIndex = 0;
                for (var value : patch.getAsJsonArray("createReplies")) navigation.replies.add(path(value));
            }
            if (patch.has("onCreateMaidBlock")) onCreateMaidBlock = config.get("onCreateMaidBlock").isJsonNull() ? null : parseBlock(config.get("onCreateMaidBlock"));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void registration(MemoryModuleType<?> type, boolean registered) throws Exception {
            var values = (Map) read(Brain.class, "memories", maid.getBrain());
            if (registered) values.putIfAbsent(type, Optional.empty()); else values.remove(type);
        }

        boolean registered(MemoryModuleType<?> type) { return maid.getBrain().checkMemory(type, MemoryStatus.REGISTERED); }

        JsonObject snapshot() throws Exception {
            var memory = new JsonObject();
            var flags = new JsonObject();
            flags.addProperty("walk", registered(MemoryModuleType.WALK_TARGET));
            flags.addProperty("path", registered(MemoryModuleType.PATH));
            flags.addProperty("cantReachSince", registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE));
            memory.add("registered", flags);
            WalkTarget walk = registered(MemoryModuleType.WALK_TARGET) ? maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) : null;
            if (walk == null) memory.add("walk", JsonNull.INSTANCE);
            else {
                var value = new JsonObject();
                value.add("block", block(walk.getTarget().currentBlockPosition()));
                value.addProperty("speed", walk.getSpeedModifier());
                value.addProperty("closeEnough", walk.getCloseEnoughDist());
                value.addProperty("entityTrackerSpectator", walk.getTarget() instanceof EntityTracker tracker && tracker.getEntity().isSpectator());
                memory.add("walk", value);
            }
            memory.addProperty("path", registered(MemoryModuleType.PATH) ? id(maid.getBrain().getMemory(MemoryModuleType.PATH).orElse(null)) : null);
            memory.addProperty("cantReachSince", registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                ? maid.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null) : null);
            var state = new JsonObject();
            state.addProperty("status", sink.getStatus().name());
            state.addProperty("remainingCooldown", (Integer) read(MoveToTargetSink.class, "remainingCooldown", sink));
            state.addProperty("path", id((net.minecraft.world.level.pathfinder.Path) read(MoveToTargetSink.class, "path", sink)));
            BlockPos last = (BlockPos) read(MoveToTargetSink.class, "lastTargetPos", sink);
            state.add("lastTargetPos", last == null ? JsonNull.INSTANCE : block(last));
            state.addProperty("speedModifier", (Float) read(MoveToTargetSink.class, "speedModifier", sink));
            state.addProperty("endTimestamp", (Long) read(Behavior.class, "endTimestamp", sink));
            var result = new JsonObject();
            result.add("memory", memory); result.add("sink", state); result.add("navigation", navigation.snapshot());
            return result;
        }

        JsonObject actualPaths() {
            var result = new JsonObject();
            for (var entry : paths.entrySet()) {
                var value = new JsonObject();
                var path = entry.getValue();
                value.addProperty("canReach", path.canReach()); value.add("target", block(path.getTarget()));
                var nodes = new JsonArray();
                for (int index = 0; index < path.getNodeCount(); index++) nodes.add(block(path.getNode(index).asBlockPos()));
                value.add("nodes", nodes); result.add(entry.getKey(), value);
            }
            return result;
        }

        net.minecraft.world.level.pathfinder.Path path(JsonElement value) {
            if (value == null || value.isJsonNull()) return null;
            var path = paths.get(value.getAsString()); require(path != null, "Unknown controlled path"); return path;
        }

        String id(net.minecraft.world.level.pathfinder.Path path) {
            if (path == null) return null;
            String id = pathIds.get(path); require(id != null, "Unobserved path identity"); return id;
        }

        @Override public void close() {
            recording = false;
            try { maid.discard(); } finally { target.discard(); }
        }
    }

    private static final class ControlledNavigation extends MaidPathNavigation {
        final Fixture fixture;
        final List<net.minecraft.world.level.pathfinder.Path> replies = new ArrayList<>();
        int replyIndex;
        net.minecraft.world.level.pathfinder.Path current;
        boolean done = true, stuck, moveResult = true;

        ControlledNavigation(ProbeMaid maid, Fixture fixture) { super(maid, maid.level()); this.fixture = fixture; }

        @Override public net.minecraft.world.level.pathfinder.Path createPath(BlockPos target, int accuracy) {
            require(fixture.recording && replyIndex < replies.size(), "Controlled path replies exhausted");
            boolean coordinateWrapper = false;
            for (var frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(PathNavigation.class.getName()) && frame.getMethodName().equals("createPath")) {
                    coordinateWrapper = true; break;
                }
            }
            var result = replies.get(replyIndex++);
            var call = new JsonObject();
            call.addProperty("op", "createPath");
            call.addProperty("entry", coordinateWrapper ? "coordinate_wrapper" : "block");
            call.add("target", block(target)); call.addProperty("accuracy", accuracy); call.addProperty("resultPath", fixture.id(result));
            fixture.calls.add(call);
            int index = fixture.createIndex++;
            if (coordinateWrapper) fixture.fallbackQuery = target.immutable();
            if (fixture.onCreateMaidBlock != null) {
                var mutation = new JsonObject();
                mutation.addProperty("createIndex", index);
                mutation.add("from", block(fixture.maid.blockPosition()));
                mutation.add("to", block(fixture.onCreateMaidBlock));
                setBlockPosition(fixture.maid, fixture.onCreateMaidBlock);
                mutation.add("actualAfterBlock", block(fixture.maid.blockPosition()));
                fixture.onCreateMaidBlock = null;
                fixture.callbackObservations.add(mutation);
            }
            return result;
        }

        @Override public boolean moveTo(net.minecraft.world.level.pathfinder.Path path, double speed) {
            var before = snapshot();
            current = path; done = false;
            var call = new JsonObject();
            call.addProperty("op", "moveTo"); call.addProperty("path", fixture.id(path));
            call.addProperty("speed", speed); call.addProperty("result", moveResult);
            fixture.calls.add(call);
            var observation = copy(call); observation.remove("op");
            observation.add("before", before); observation.add("after", snapshot());
            fixture.moveObservations.add(observation);
            return moveResult;
        }

        @Override public void stop() {
            current = null; done = true;
            if (fixture != null && fixture.recording) {
                var call = new JsonObject(); call.addProperty("op", "stopNavigation"); fixture.calls.add(call);
            }
        }

        @Override public net.minecraft.world.level.pathfinder.Path getPath() { return current; }
        @Override public boolean isDone() { return done; }
        @Override public boolean isStuck() { return stuck; }
        @Override public boolean isStableDestination(BlockPos target) {
            if (fixture == null) return super.isStableDestination(target);
            boolean stable = fixture.config.get("stableDestination").getAsBoolean();
            if (fixture.recording) {
                var item = new JsonObject(); item.add("block", block(target)); item.addProperty("stable", stable);
                fixture.stabilityChecks.add(item);
            }
            return stable;
        }

        JsonObject snapshot() {
            var result = new JsonObject(); result.addProperty("path", fixture.id(current));
            result.addProperty("done", done); result.addProperty("stuck", stuck); return result;
        }
    }

    private static final class Target extends ServerPlayer {
        boolean spectator;
        Target(MinecraftServer server, ServerLevel level, GameProfile profile) { super(server, level, profile); }
        @Override public boolean isSpectator() { return spectator; }
    }

    private static final class ProbeMaid extends EntityMaid {
        Fixture fixture;
        ProbeMaid(ServerLevel level) { super(level); }
        @Override public PathNavigation getNavigation() { return fixture == null ? super.getNavigation() : fixture.navigation; }
        @Override public RandomSource getRandom() { return fixture == null || fixture.entityRandom == null ? super.getRandom() : fixture.entityRandom; }
        @Override public float getPathfindingMalus(BlockPathTypes type) {
            if (fixture == null || !fixture.recording) return super.getPathfindingMalus(type);
            float result = fixture.config.get("malus").getAsFloat();
            var observation = new JsonObject(); observation.addProperty("type", type.name()); observation.addProperty("value", result);
            fixture.malusChecks.add(observation); return result;
        }
        @Override public float getWalkTargetValue(BlockPos pos) {
            if (fixture == null || !fixture.recording) return super.getWalkTargetValue(pos);
            require(fixture.config.get("scoreMode").getAsString().equals("constant"), "Unsupported controlled score");
            var observation = new JsonObject(); observation.add("block", block(pos)); observation.addProperty("score", 1.0F);
            fixture.scores.add(observation); return 1.0F;
        }
    }

    private static final class RandomFeed implements RandomSource {
        final RandomSource delegate;
        final boolean world;
        final List<Integer> integers = new ArrayList<>();
        int integerIndex;
        Fixture fixture;
        JsonArray calls = new JsonArray();

        RandomFeed(long seed, boolean world, JsonArray script) {
            delegate = RandomSource.create(seed); this.world = world;
            for (var value : script) integers.add(value.getAsInt());
        }

        private boolean active() { return fixture != null && fixture.recording; }
        private void record(String method, Number value, Integer bound) {
            if (!active()) return;
            require(calls.size() < 1024, "Component RNG call capacity exceeded");
            if (!world) for (var frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals("net.minecraft.world.entity.ai.util.DefaultRandomPos")) {
                    fixture.fallbackSeen = true; break;
                }
            }
            var item = new JsonObject(); item.addProperty("method", method); item.addProperty("value", value);
            if (bound != null) item.addProperty("bound", bound); calls.add(item);
        }

        @Override public int nextInt(int bound) {
            int result;
            if (world && active()) {
                require(integerIndex < integers.size(), "Controlled world integer stream exhausted");
                result = integers.get(integerIndex++);
                require(result >= 0 && result < bound, "Controlled world integer outside actual requested bound");
                var call = new JsonObject(); call.addProperty("op", "worldNextInt");
                call.addProperty("bound", bound); call.addProperty("value", result); fixture.calls.add(call);
            } else result = delegate.nextInt(bound);
            record("nextInt", result, bound); return result;
        }

        private void requireEntity() { require(!world || !active(), "Unexpected non-integer world RNG call"); }
        @Override public RandomSource fork() { throw new IllegalStateException("RNG fork outside component contract"); }
        @Override public PositionalRandomFactory forkPositional() { throw new IllegalStateException("Positional RNG outside component contract"); }
        @Override public void setSeed(long seed) { requireEntity(); delegate.setSeed(seed); record("setSeed", seed, null); }
        @Override public int nextInt() { requireEntity(); int value = delegate.nextInt(); record("nextInt", value, null); return value; }
        @Override public long nextLong() { requireEntity(); long value = delegate.nextLong(); record("nextLong", value, null); return value; }
        @Override public boolean nextBoolean() { requireEntity(); boolean value = delegate.nextBoolean(); record("nextBoolean", value ? 1 : 0, null); return value; }
        @Override public float nextFloat() { requireEntity(); float value = delegate.nextFloat(); record("nextFloat", value, null); return value; }
        @Override public double nextDouble() { requireEntity(); double value = delegate.nextDouble(); record("nextDouble", value, null); return value; }
        @Override public double nextGaussian() { requireEntity(); double value = delegate.nextGaussian(); record("nextGaussian", value, null); return value; }
    }

    private static Object read(Class<?> owner, String name, Object instance) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }

    private static JsonObject failure(String id, int rebuild, Throwable failure) {
        var result = new JsonObject(); result.addProperty("id", id); result.addProperty("rebuild", rebuild);
        result.addProperty("errorType", failure.getClass().getSimpleName()); result.addProperty("error", safeMessage(failure)); return result;
    }

    private static void merge(JsonObject destination, JsonObject patch) {
        for (var entry : patch.entrySet()) {
            if (entry.getValue().isJsonObject() && destination.has(entry.getKey()) && destination.get(entry.getKey()).isJsonObject())
                merge(destination.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject());
            else destination.add(entry.getKey(), frozen(entry.getValue()));
        }
    }

    private static void setBlockPosition(net.minecraft.world.entity.Entity entity, BlockPos block) {
        entity.setPos(block.getX() + 0.5D, block.getY(), block.getZ() + 0.5D);
    }

    private static BlockPos parseBlock(JsonElement value) {
        var array = value.getAsJsonArray(); require(array.size() == 3, "Three coordinate values required");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static JsonArray block(BlockPos pos) {
        var result = new JsonArray(); result.add(pos.getX()); result.add(pos.getY()); result.add(pos.getZ()); return result;
    }

    private static JsonArray position(net.minecraft.world.phys.Vec3 pos) {
        require(Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z), "Non-finite observed position");
        var result = new JsonArray(); result.add(pos.x); result.add(pos.y); result.add(pos.z); return result;
    }

    private static UUID stableUuid(JsonObject spec, String role) {
        return UUID.nameUUIDFromBytes(("MOVE_SINK_CORE_V1/" + spec.get("id").getAsString() + "/" + role).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonElement frozen(JsonElement value) { return JsonParser.parseString(GSON.toJson(value)); }
    private static JsonObject copy(JsonObject value) { return frozen(value).getAsJsonObject(); }
    private static String sha(byte[] bytes) throws Exception { return MoveReferenceIdentity.sha(bytes); }
    private static void write(Path destination, JsonObject value) throws Exception {
        require(!Files.exists(destination), "Refusing to overwrite move evidence");
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(temporary, destination);
    }
    private static String safeMessage(Throwable failure) {
        String value = failure.getMessage();
        return value != null && !value.contains("\\") && !value.contains("/") && value.length() <= 200 ? value : "See local log; no private path exported";
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

