package com.tianluoqaq.tlmnh.reference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidPathNavigation;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 原LOOK生命周期及三行为Brain组件，真实方法加受控依赖，不是自然Brain */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class LookReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static volatile boolean pending;
    private static boolean failed;

    private LookReferenceProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.lookProbe") || event.phase != TickEvent.Phase.END || failed || pending) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-look-reference");
                for (String child : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(child));
                TouhouLittleMaid.LOGGER.info("TLMNH_LOOK_REFERENCE_READY contract=LOOK_SINK_CORE_V1 + FOLLOW_MOVE_LOOK_COMPONENT_V2");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var requests = new ArrayList<Path>();
                stream.forEach(requests::add);
                requests.sort(Path::compareTo);
                if (!requests.isEmpty()) handle(requests.get(0));
            }
        } catch (Exception | LinkageError failure) {
            failed = true;
            TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_REFERENCE_IO_FAILED", failure);
        }
    }

    private static void handle(Path path) throws Exception {
        String name = path.getFileName().toString();
        var response = new JsonObject();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= 16384, "Invalid look request");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> {
                    response.addProperty("contract", "LOOK_SINK_CORE_V1 + FOLLOW_MOVE_LOOK_COMPONENT_V2");
                    response.addProperty("worldLoaded", minecraft.level != null && minecraft.getSingleplayerServer() != null);
                    response.addProperty("spectator", minecraft.player != null && minecraft.player.isSpectator());
                    response.addProperty("pending", pending);
                }
                case "lab_create" -> response = ReferenceScene.createLab();
                case "lab_open" -> response = ReferenceScene.openLab();
                case "capture_look" -> {
                    require(request.get("repetitions").getAsInt() == 3 && request.get("repetitions").getAsDouble() == 3,
                        "Exactly three fresh entity/state builds required");
                    var server = minecraft.getSingleplayerServer();
                    require(server != null && minecraft.level != null && minecraft.player != null
                        && minecraft.player.isSpectator() && minecraft.player.getAbilities().invulnerable,
                        "Protected integrated reference spectator required");
                    require(server.getWorldData().getLevelName().equals("TLMNH-Reference-Lab"), "Dedicated reference lab required");
                    minecraft.setScreen(null);
                    String fault = request.has("fault") ? request.get("fault").getAsString() : "none";
                    require(Set.of("none", "deadline-equality-plus-one", "target-feet", "look-priority-last").contains(fault), "Unsupported actual input fault");
                    pending = true;
                    Files.delete(path);
                    server.execute(() -> executeCapture(server, name, fault));
                    return;
                }
                case "shutdown" -> minecraft.stop();
                default -> throw new IllegalArgumentException("Unsupported look command");
            }
            response.addProperty("status", "complete");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive");
            TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_REFERENCE_FAILURE request={}", name, failure);
            response.addProperty("errorType", failure.getClass().getSimpleName());
            response.addProperty("error", safeMessage(failure));
        }
        response.addProperty("request", name);
        write(root.resolve("responses").resolve(name), response);
        Files.deleteIfExists(path);
    }

    private static void executeCapture(MinecraftServer server, String name, String fault) {
        var response = new JsonObject();
        try {
            require(server.isSameThread(), "Actual server thread required");
            var report = capture(server, fault);
            Path destination = root.resolve("captures").resolve(name);
            write(destination, report);
            response.addProperty("capture", "captures/" + name);
            response.addProperty("captureSha256", sha(Files.readAllBytes(destination)));
            response.addProperty("status", report.get("complete").getAsBoolean() ? "complete" : "inconclusive");
            response.addProperty("verdict", report.get("complete").getAsBoolean() ? "CAPTURED_NOT_COMPARED" : "COMPONENT_CASE_FAILED");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive");
            TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_REFERENCE_FAILURE request={}", name, failure);
            response.addProperty("errorType", failure.getClass().getSimpleName());
            response.addProperty("error", safeMessage(failure));
        } finally {
            response.addProperty("request", name);
            try { write(root.resolve("responses").resolve(name), response); }
            catch (Exception failure) { failed = true; }
            pending = false;
        }
    }

    private static JsonObject capture(MinecraftServer server, String fault) throws Exception {
        require(server.isSameThread(), "Actual server thread required");
        require(Set.copyOf(ModList.get().getMods().stream().map(info -> info.getModId()).toList())
            .equals(Set.of("minecraft", "forge", "touhou_little_maid")), "Minimal original reference mods required");
        require(ModList.get().getModContainerById("forge").orElseThrow().getModInfo().getVersion().toString().equals("47.2.0"), "Forge 47.2.0 required");
        var contract = JsonParser.parseString(new String(LookReferenceIdentity.resource("contract.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        require(contract.get("schema").getAsString().equals("look-reference-contract-v1") && contract.get("rebuilds").getAsInt() == 3,
            "Unsupported look contract");
        var report = new JsonObject();
        report.addProperty("schema", "look-reference-capture-v1");
        report.addProperty("inputObservationSchema", "look-component-input-observations-v1");
        report.add("contracts", copy(contract).get("contracts"));
        report.addProperty("contractSha256", sha(LookReferenceIdentity.resource("contract.json")));
        report.addProperty("scope", contract.get("scope").getAsString());
        report.addProperty("fault", fault);
        report.addProperty("naturalBrainVerified", false);
        report.addProperty("nativeHelperTicked", false);
        report.addProperty("nativeNavigationVerified", false);
        report.addProperty("ownerResolver", "controlled ProbeMaid.getOwner; synthetic unregistered ServerPlayer; not real online owner");
        report.addProperty("visibilitySource", "real EntityTracker.isVisibleBy -> real NearestVisibleLivingEntities.contains delegate; controlled memory candidates, no sensor ticking");
        report.add("identity", LookReferenceIdentity.capture());
        report.addProperty("complete", true);
        var rows = new JsonArray(); report.add("cases", rows);
        prepareAir(server.overworld());
        long started = System.nanoTime();
        require(contract.getAsJsonArray("cases").size() <= 32, "Look case capacity exceeded");
        outer: for (var value : contract.getAsJsonArray("cases")) for (int rebuild = 0; rebuild < 3; rebuild++) {
            require(System.nanoTime() - started < 60_000_000_000L, "Look component capture deadline exceeded");
            var row = runCase(server, value.getAsJsonObject(), rebuild, fault); rows.add(row);
            if (!row.get("complete").getAsBoolean()) { report.addProperty("complete", false); break outer; }
        }
        return report;
    }

    private static void prepareAir(ServerLevel level) {
        for (int x : new int[] {-1, 0, 1}) for (int z : new int[] {-1, 0, 1}) level.getChunk(x, z);
        for (int x = -2; x <= 4; x++) for (int z = -2; z <= 12; z++) for (int y = 191; y <= 196; y++) {
            var at = new BlockPos(x, y, z);
            require(level.getBlockState(at).is(Blocks.AIR) && level.getBlockEntity(at) == null,
                "Dedicated component read region must be pure loaded air");
        }
    }

    private static JsonObject runCase(MinecraftServer server, JsonObject spec, int rebuild, String fault) throws Exception {
        var level = server.overworld();
        require(level.getLevelData() instanceof ServerLevelData, "Mutable original level clock required");
        var data = (ServerLevelData) level.getLevelData();
        long oldTime = level.getGameTime(); RandomSource oldRandom = level.getRandom();
        Field randomField = Level.class.getDeclaredField("random"); randomField.setAccessible(true);
        var feed = new RandomFeed(spec.getAsJsonArray("worldInts"));
        var row = new JsonObject(); row.addProperty("id", spec.get("id").getAsString()); row.addProperty("rebuild", rebuild);
        row.addProperty("contract", spec.get("kind").getAsString().equals("look") ? "LOOK_SINK_CORE_V1" : "FOLLOW_MOVE_LOOK_COMPONENT_V2");
        row.add("input", copy(spec)); row.addProperty("complete", false);
        Fixture fixture = null; Throwable cleanupFailure = null;
        try {
            randomField.set(level, feed); require(level.getRandom() == feed, "World RNG adapter unavailable");
            data.setGameTime(spec.getAsJsonArray("steps").get(0).getAsJsonObject().get("methodTime").getAsLong());
            fixture = new Fixture(server, level, spec, feed, fault);
            row.add("initialState", fixture.snapshot()); row.add("initialInputObservation", fixture.inputs());
            row.add("registeredBehaviors", fixture.registrations());
            row.addProperty("coreActivityActive", fixture.maid.getBrain().getActiveActivities().contains(Activity.CORE));
            var steps = new JsonArray(); row.add("steps", steps);
            require(spec.getAsJsonArray("steps").size() <= 16, "Look step capacity exceeded");
            int index = 0;
            for (var step : spec.getAsJsonArray("steps")) {
                var observed = fixture.runStep(step.getAsJsonObject(), index++, data); steps.add(observed);
                require(observed.get("complete").getAsBoolean(), "Original component invocation failed; inspect retained step and log");
            }
            require(feed.index == feed.values.size(), "Controlled world stream not consumed exactly");
            row.addProperty("complete", true);
        } catch (Exception | LinkageError problem) {
            TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_CASE_FAILED id={} rebuild={}", spec.get("id").getAsString(), rebuild, problem);
            row.add("failure", failure(problem));
        } finally {
            if (fixture != null) try { fixture.close(); } catch (Exception | LinkageError problem) { cleanupFailure = problem; }
            try { randomField.set(level, oldRandom); require(level.getRandom() == oldRandom, "World RNG restore failed"); }
            catch (Exception | LinkageError problem) { cleanupFailure = combine(cleanupFailure, problem); }
            try { data.setGameTime(oldTime); require(level.getGameTime() == oldTime, "World clock restore failed"); }
            catch (Exception | LinkageError problem) { cleanupFailure = combine(cleanupFailure, problem); }
            row.addProperty("cleanupComplete", cleanupFailure == null);
            row.addProperty("worldRandomRestored", level.getRandom() == oldRandom);
            row.addProperty("worldClockRestored", level.getGameTime() == oldTime);
            if (cleanupFailure != null) {
                row.addProperty("complete", false); row.add("cleanupFailure", failure(cleanupFailure));
                TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_CLEANUP_FAILED", cleanupFailure);
            }
        }
        return row;
    }

    private static final class Fixture {
        final ServerLevel level;
        final ProbeMaid maid;
        final ServerPlayer owner;
        final LookAtTargetSink look = new LookAtTargetSink(45, 90);
        final MoveToTargetSink move = new MoveToTargetSink();
        final MaidFollowOwnerTask follow = new MaidFollowOwnerTask(0.5F, 2);
        final ControlledNavigation navigation;
        final RecordingLookControl control;
        final RandomFeed feed;
        final JsonObject config;
        final String id, kind, fault;
        final IdentityHashMap<Object, String> trackerIds = new IdentityHashMap<>();
        JsonArray calls = new JsonArray();
        JsonObject invocation;
        boolean inMethod;

        Fixture(MinecraftServer server, ServerLevel level, JsonObject spec, RandomFeed feed, String fault) {
            this.level = level; this.feed = feed; this.fault = fault;
            id = spec.get("id").getAsString(); kind = spec.get("kind").getAsString(); config = copy(spec.getAsJsonObject("initial"));
            owner = new ServerPlayer(server, level, new GameProfile(stableUuid(id, "owner"), "TLMNHLookOwner"));
            maid = new ProbeMaid(level); maid.setUUID(stableUuid(id, "maid")); maid.setNoAi(true); maid.setHomeModeEnable(false);
            maid.setPos(0.5D, 192, 0.5D); maid.restrictTo(maid.blockPosition(), 8);
            require(level.getEntity(maid.getUUID()) == null && level.getEntity(owner.getUUID()) == null
                && server.getPlayerList().getPlayer(owner.getUUID()) == null, "Component entities must be unregistered");
            maid.fixture = this;
            navigation = new ControlledNavigation(maid, this); control = new RecordingLookControl(maid, this);
            maid.install(navigation, control);
            var brain = maid.getBrain();
            brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(fault.equals("look-priority-last") ? 4 : 0, look), Pair.of(2, move), Pair.of(3, follow)));
            brain.setCoreActivities(Set.of(Activity.CORE)); brain.setDefaultActivity(Activity.CORE); brain.useDefaultActivity();
            apply(config, true); feed.fixture = this;
        }

        JsonObject runStep(JsonObject spec, int index, ServerLevelData data) throws Exception {
            var step = new JsonObject(); step.addProperty("index", index); step.addProperty("operation", spec.get("operation").getAsString());
            step.add("externalPatch", copy(spec.getAsJsonObject("externalPatch")));
            var observations = new JsonObject(); observations.add("beforePatch", inputs());
            apply(spec.getAsJsonObject("externalPatch"), false);
            long requested = spec.get("methodTime").getAsLong();
            long consumed = fault.equals("deadline-equality-plus-one") && id.equals("L01_min_duration") && index == 2 ? requested + 1 : requested;
            data.setGameTime(consumed);
            observations.add("beforeMethod", inputs()); step.add("inputObservations", observations);
            step.addProperty("requestedMethodTime", requested); step.addProperty("actualMethodTime", consumed);
            step.add("before", snapshot()); calls = new JsonArray();
            step.addProperty("complete", false);
            try {
                Object returned = invoke(spec.get("operation").getAsString(), consumed);
                step.add("methodReturn", returned instanceof Boolean value ? GSON.toJsonTree(value) : JsonNull.INSTANCE);
                step.addProperty("complete", true);
            } catch (Exception | LinkageError problem) {
                step.add("failure", failure(problem));
                TouhouLittleMaid.LOGGER.error("TLMNH_LOOK_METHOD_FAILED id={} index={}", id, index, problem);
            } finally {
                step.add("methodInvocation", invocation); step.add("callTrace", calls);
                step.add("after", snapshot()); observations.add("afterMethod", inputs());
            }
            return step;
        }

        Object invoke(String operation, long actualTime) throws Exception {
            require(!inMethod && level.getGameTime() == actualTime, "Invocation input binding failed");
            invocation = new JsonObject(); invocation.addProperty("operation", operation);
            invocation.add("timeArgument", operation.equals("brainPasses") ? JsonNull.INSTANCE : GSON.toJsonTree(actualTime));
            invocation.addProperty("worldGameTime", level.getGameTime());
            invocation.addProperty("entityIsConfiguredMaid", maid.level() == level);
            invocation.addProperty("serverThread", level.getServer().isSameThread());
            inMethod = true;
            try {
                return switch (operation) {
                    case "tryStart" -> look.tryStart(level, maid, actualTime);
                    case "tickOrStop" -> { look.tickOrStop(level, maid, actualTime); yield null; }
                    case "doStop" -> { look.doStop(level, maid, actualTime); yield null; }
                    case "brainPasses" -> {
                        require(kind.equals("combined"), "Brain passes outside combined component");
                        invokeBrainPass("startEachNonRunningBehavior"); invokeBrainPass("tickEachRunningBehavior"); yield null;
                    }
                    default -> throw new IllegalArgumentException("Unsupported original look operation");
                };
            } finally { inMethod = false; }
        }

        void invokeBrainPass(String name) throws Exception {
            Method method = Brain.class.getDeclaredMethod(name, ServerLevel.class, LivingEntity.class); method.setAccessible(true);
            method.invoke(maid.getBrain(), level, maid);
        }

        void apply(JsonObject patch, boolean initial) {
            patch.entrySet().forEach(entry -> config.add(entry.getKey(), entry.getValue().deepCopy()));
            if (initial || patch.has("targetPosition")) {
                Vec3 at = parsePosition(config.get("targetPosition")); owner.setPos(at.x, at.y, at.z);
            }
            if (initial || patch.has("lookPresent") || patch.has("trackEye")) {
                if (config.get("lookPresent").getAsBoolean()) {
                    boolean eye = config.get("trackEye").getAsBoolean();
                    if (fault.equals("target-feet") && id.equals("L01_min_duration")) eye = false;
                    maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(owner, eye));
                } else maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
            if (initial || patch.has("visibleMemory")) {
                String value = config.get("visibleMemory").getAsString();
                require(Set.of("owner", "empty").contains(value), "Unsupported visible memory input");
                maid.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                    new RecordingVisibleEntities(maid, value.equals("owner") ? List.of(owner) : List.of(), this));
            }
        }

        JsonArray registrations() throws Exception {
            var result = new JsonArray();
            var priorities = (Map<?, ?>) read(Brain.class, "availableBehaviorsByPriority", maid.getBrain());
            for (var priority : priorities.entrySet()) for (var activity : ((Map<?, ?>) priority.getValue()).entrySet())
                for (Object behavior : (Set<?>) activity.getValue()) {
                    String role = behavior == look ? "look" : behavior == move ? "move" : behavior == follow ? "follow" : "other";
                    require(!role.equals("other") && priority.getKey() instanceof Integer, "Unexpected registered component behavior");
                    var item = new JsonObject(); item.addProperty("role", role); item.addProperty("priority", (Integer) priority.getKey());
                    item.addProperty("coreActivity", activity.getKey() == Activity.CORE); item.addProperty("class", behavior.getClass().getName()); result.add(item);
                }
            return result;
        }

        JsonObject inputs() throws Exception {
            var result = new JsonObject(); result.addProperty("worldGameTime", level.getGameTime());
            result.add("maidPosition", position(maid.position())); result.add("ownerPosition", position(owner.position()));
            result.addProperty("ownerEyeHeight", (double) owner.getEyeHeight()); result.add("ownerEyePosition", position(owner.position().add(0, owner.getEyeHeight(), 0)));
            result.addProperty("ownerAlive", owner.isAlive()); result.addProperty("ownerSpectator", owner.isSpectator());
            result.addProperty("ownerResolved", maid.getOwner() == owner); result.addProperty("radius", maid.getRestrictRadius());
            result.addProperty("home", maid.isHomeModeEnable()); result.addProperty("canBrainMove", maid.canBrainMoving());
            result.addProperty("breathing", maid.getSwimManager().isGoingToBreath());
            result.addProperty("lookRegistered", maid.getBrain().checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED));
            result.add("look", tracker(maid.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)));
            var visible = maid.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).orElse(null);
            result.addProperty("visibleMemoryPresent", visible != null);
            result.addProperty("visibleCandidateContainsOwner", visible != null && ((List<?>) read(NearestVisibleLivingEntities.class, "nearbyEntities", visible)).contains(owner));
            result.addProperty("worldRegisteredMaid", level.getEntity(maid.getUUID()) == maid);
            result.addProperty("worldRegisteredOwner", level.getEntity(owner.getUUID()) == owner);
            return result;
        }

        JsonObject snapshot() throws Exception {
            var result = new JsonObject(); var memory = new JsonObject();
            memory.add("look", tracker(maid.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null)));
            WalkTarget walk = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            if (walk == null) memory.add("walk", JsonNull.INSTANCE);
            else { var value = new JsonObject(); value.add("tracker", tracker(walk.getTarget())); value.addProperty("speed", walk.getSpeedModifier()); value.addProperty("closeEnough", walk.getCloseEnoughDist()); memory.add("walk", value); }
            memory.addProperty("path", navigation.id(maid.getBrain().getMemory(MemoryModuleType.PATH).orElse(null)));
            var cant = maid.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(null);
            memory.add("cantReachSince", cant == null ? JsonNull.INSTANCE : GSON.toJsonTree(cant));
            result.add("memory", memory); var tasks = new JsonObject();
            tasks.add("look", behavior(look));
            var moveState = behavior(move);
            moveState.addProperty("remainingCooldown", (Integer) read(MoveToTargetSink.class, "remainingCooldown", move));
            moveState.addProperty("path", navigation.id((net.minecraft.world.level.pathfinder.Path) read(MoveToTargetSink.class, "path", move)));
            BlockPos last = (BlockPos) read(MoveToTargetSink.class, "lastTargetPos", move);
            moveState.add("lastTargetPos", last == null ? JsonNull.INSTANCE : block(last));
            moveState.addProperty("speedModifier", (Float) read(MoveToTargetSink.class, "speedModifier", move));
            tasks.add("move", moveState); tasks.add("follow", behavior(follow)); result.add("tasks", tasks);
            result.add("navigation", navigation.snapshot()); result.add("lookControl", control.snapshot());
            result.addProperty("worldIntegerIndex", feed.index); return result;
        }

        JsonElement tracker(PositionTracker value) throws Exception {
            if (value == null) return JsonNull.INSTANCE;
            var result = new JsonObject(); result.addProperty("identity", trackerIds.computeIfAbsent(value, ignored -> "t" + (trackerIds.size() + 1)));
            result.addProperty("class", value.getClass().getName()); result.add("position", position(value.currentPosition())); result.add("block", block(value.currentBlockPosition()));
            if (value instanceof EntityTracker entity) {
                result.addProperty("target", entity.getEntity() == owner ? "owner" : "other"); result.addProperty("trackEye", (Boolean) read(EntityTracker.class, "trackEyeHeight", value));
            }
            return result;
        }

        void add(JsonObject call) { if (inMethod) { require(calls.size() < 1024, "Component call bound exceeded"); calls.add(call); } }
        void close() {
            inMethod = false; feed.fixture = null;
            try { maid.discard(); } finally { owner.discard(); }
            require(level.getEntity(maid.getUUID()) == null && level.getEntity(owner.getUUID()) == null, "Unexpected registered fixture entity");
        }
    }

    private static final class ProbeMaid extends EntityMaid {
        Fixture fixture;
        ProbeMaid(ServerLevel level) { super(level); }
        void install(ControlledNavigation navigation, RecordingLookControl look) {
            this.navigation = navigation; this.lookControl = look; this.brain = getBrain().copyWithoutBehaviors();
        }
        @Override public LivingEntity getOwner() {
            if (fixture == null) return null;
            var call = new JsonObject(); call.addProperty("op", "resolveOwner"); call.addProperty("owner", "owner"); fixture.add(call); return fixture.owner;
        }
    }

    private static final class RecordingVisibleEntities extends NearestVisibleLivingEntities {
        final Fixture fixture;
        RecordingVisibleEntities(LivingEntity maid, List<LivingEntity> values, Fixture fixture) { super(maid, values); this.fixture = fixture; }
        @Override public boolean contains(LivingEntity entity) {
            boolean result = super.contains(entity);
            var call = new JsonObject(); call.addProperty("op", "isVisibleBy"); call.addProperty("target", entity == fixture.owner ? "owner" : "other"); call.addProperty("result", result); fixture.add(call);
            return result;
        }
    }

    private static final class RecordingLookControl extends LookControl {
        final Fixture fixture;
        RecordingLookControl(ProbeMaid maid, Fixture fixture) { super(maid); this.fixture = fixture; }
        @Override public void setLookAt(Vec3 target) {
            var call = new JsonObject(); call.addProperty("op", "setLookAt"); call.add("target", position(target)); call.add("before", snapshot());
            super.setLookAt(target); call.add("after", snapshot()); fixture.add(call);
        }
        JsonObject snapshot() {
            var result = new JsonObject(); result.add("wanted", position(new Vec3(wantedX, wantedY, wantedZ)));
            result.addProperty("cooldown", lookAtCooldown); result.addProperty("yawSpeed", yMaxRotSpeed); result.addProperty("pitchSpeed", xMaxRotAngle); return result;
        }
    }

    private static final class ControlledNavigation extends MaidPathNavigation {
        final Fixture fixture;
        final IdentityHashMap<net.minecraft.world.level.pathfinder.Path, String> ids = new IdentityHashMap<>();
        net.minecraft.world.level.pathfinder.Path current;
        boolean done = true;
        ControlledNavigation(ProbeMaid maid, Fixture fixture) { super(maid, maid.level()); this.fixture = fixture; }
        @Override public net.minecraft.world.level.pathfinder.Path createPath(BlockPos target, int accuracy) {
            var result = new net.minecraft.world.level.pathfinder.Path(List.of(new Node(target.getX(), target.getY(), target.getZ())), target, true);
            ids.put(result, "p" + (ids.size() + 1));
            var call = new JsonObject(); call.addProperty("op", "createPath"); call.add("target", block(target)); call.addProperty("accuracy", accuracy); call.addProperty("result", id(result)); call.addProperty("canReach", result.canReach());
            var actual = new JsonObject(); actual.addProperty("id", id(result)); actual.add("target", block(result.getTarget())); actual.addProperty("canReach", result.canReach());
            var nodes = new JsonArray(); for (int i = 0; i < result.getNodeCount(); i++) { Node n = result.getNode(i); nodes.add(block(new BlockPos(n.x, n.y, n.z))); }
            actual.add("nodes", nodes); call.add("actualPath", actual); fixture.add(call); return result;
        }
        @Override public boolean moveTo(net.minecraft.world.level.pathfinder.Path path, double speed) {
            var before = snapshot(); current = path; done = false;
            var call = new JsonObject(); call.addProperty("op", "moveTo"); call.addProperty("path", id(path)); call.addProperty("speed", speed); call.addProperty("result", true);
            call.add("before", before); call.add("after", snapshot()); fixture.add(call); return true;
        }
        @Override public void stop() { var before = snapshot(); current = null; done = true; var call = new JsonObject(); call.addProperty("op", "stopNavigation"); call.add("before", before); call.add("after", snapshot()); fixture.add(call); }
        @Override public net.minecraft.world.level.pathfinder.Path getPath() { return current; }
        @Override public boolean isDone() { return done; }
        @Override public boolean isStuck() { return false; }
        String id(net.minecraft.world.level.pathfinder.Path value) { return value == null ? null : ids.get(value); }
        JsonObject snapshot() { var result = new JsonObject(); result.addProperty("path", id(current)); result.addProperty("done", done); result.addProperty("stuck", false); result.addProperty("source", "controlled full path; no native navigation tick"); return result; }
    }

    private static final class RandomFeed implements RandomSource {
        final RandomSource delegate = RandomSource.create(4408L);
        final List<Integer> values = new ArrayList<>(); int index; Fixture fixture;
        RandomFeed(JsonArray source) { source.forEach(value -> values.add(value.getAsInt())); }
        boolean active() { return fixture != null && fixture.inMethod; }
        @Override public int nextInt(int bound) {
            if (!active()) return delegate.nextInt(bound);
            require(index < values.size(), "Controlled world integer stream exhausted");
            int value = values.get(index++); require(value >= 0 && value < bound, "World integer outside actual requested bound");
            var call = new JsonObject(); call.addProperty("op", "worldNextInt"); call.addProperty("bound", bound); call.addProperty("value", value); fixture.add(call); return value;
        }
        void ordinary() { require(!active(), "Unexpected world random operation in selected component"); }
        @Override public RandomSource fork() { ordinary(); return delegate.fork(); }
        @Override public PositionalRandomFactory forkPositional() { ordinary(); return delegate.forkPositional(); }
        @Override public void setSeed(long seed) { ordinary(); delegate.setSeed(seed); }
        @Override public int nextInt() { ordinary(); return delegate.nextInt(); }
        @Override public long nextLong() { ordinary(); return delegate.nextLong(); }
        @Override public boolean nextBoolean() { ordinary(); return delegate.nextBoolean(); }
        @Override public float nextFloat() { ordinary(); return delegate.nextFloat(); }
        @Override public double nextDouble() { ordinary(); return delegate.nextDouble(); }
        @Override public double nextGaussian() { ordinary(); return delegate.nextGaussian(); }
    }

    private static JsonObject behavior(Behavior<?> value) throws Exception {
        var result = new JsonObject(); result.addProperty("status", value.getStatus().name()); result.addProperty("endTimestamp", (Long) read(Behavior.class, "endTimestamp", value)); return result;
    }
    private static Object read(Class<?> owner, String name, Object instance) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
    private static UUID stableUuid(String id, String role) { return UUID.nameUUIDFromBytes(("LOOK_COMPONENT_V1/" + id + '/' + role).getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject failure(Throwable value) { var result = new JsonObject(); result.addProperty("type", value.getClass().getSimpleName()); result.addProperty("reason", safeMessage(value)); return result; }
    private static Throwable combine(Throwable previous, Throwable next) { if (previous == null) return next; previous.addSuppressed(next); return previous; }
    private static Vec3 parsePosition(JsonElement value) { var a = value.getAsJsonArray(); require(a.size() == 3, "Three position coordinates required"); return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()); }
    private static JsonArray position(Vec3 value) { require(Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z), "Non-finite component position"); var a = new JsonArray(); a.add(value.x); a.add(value.y); a.add(value.z); return a; }
    private static JsonArray block(BlockPos value) { var a = new JsonArray(); a.add(value.getX()); a.add(value.getY()); a.add(value.getZ()); return a; }
    private static JsonObject copy(JsonObject value) { return JsonParser.parseString(GSON.toJson(value)).getAsJsonObject(); }
    private static String sha(byte[] value) throws Exception { return LookReferenceIdentity.sha(value); }
    private static String safeMessage(Throwable failure) { String value = failure.getMessage(); return value != null && !value.contains("\\") && !value.contains("/") && value.length() <= 200 ? value : "inspect_retained_local_log"; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void write(Path destination, JsonObject value) throws Exception {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "Refusing to overwrite look evidence");
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); Files.move(temporary, destination);
    }
}
