package com.tianluoqaq.tlmnh.reference;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidNavigationManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 原任务组件观察器；真实实体未登记到世界，不执行完整Brain或自然导航 */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class FollowReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6";
    private static final String AGENT = "com.tianluoqaq.tlmnh.geometry.identity.GeometryDefinitionAgent";
    private static Path root;
    private static volatile boolean pending;
    private static boolean failed;

    private FollowReferenceProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.followProbe") || event.phase != TickEvent.Phase.END || failed || pending) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-follow-reference");
                for (String child : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(child));
                TouhouLittleMaid.LOGGER.info("TLMNH_FOLLOW_REFERENCE_READY contract=FOLLOW_CORE_V1");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var requests = new ArrayList<Path>();
                stream.forEach(requests::add);
                requests.sort(Path::compareTo);
                if (!requests.isEmpty()) handle(requests.get(0));
            }
        } catch (Exception | LinkageError failure) {
            failed = true;
            TouhouLittleMaid.LOGGER.error("TLMNH_FOLLOW_REFERENCE_IO_FAILED type={}", failure.getClass().getSimpleName());
        }
    }

    private static void handle(Path path) throws Exception {
        String name = path.getFileName().toString();
        var response = new JsonObject();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= 16384, "Invalid follow request");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> {
                    response.addProperty("contract", "FOLLOW_CORE_V1");
                    response.addProperty("worldLoaded", minecraft.level != null && minecraft.getSingleplayerServer() != null);
                    response.addProperty("spectator", minecraft.player != null && minecraft.player.isSpectator());
                    response.addProperty("pending", pending);
                }
                case "lab_create" -> response = ReferenceScene.createLab();
                case "lab_open" -> response = ReferenceScene.openLab();
                case "capture_follow" -> {
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
                default -> throw new IllegalArgumentException("Unsupported follow command");
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
        var mods = Set.copyOf(ModList.get().getMods().stream().map(info -> info.getModId()).toList());
        require(mods.equals(Set.of("minecraft", "forge", "touhou_little_maid")), "Minimal original reference mods required");
        require(ModList.get().getModContainerById("forge").orElseThrow().getModInfo().getVersion().toString().equals("47.2.0"),
            "Forge 47.2.0 required");
        var contract = JsonParser.parseString(new String(resource("contract.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        require(contract.get("schema").getAsString().equals("follow-core-contract-v1")
            && contract.get("upstreamCommit").getAsString().equals(BASE) && contract.get("rebuilds").getAsInt() == 3,
            "Unsupported follow contract");
        var rows = new JsonArray();
        var report = new JsonObject();
        report.addProperty("complete", true);
        report.add("identity", identity());
        long started = System.nanoTime();
        require(contract.getAsJsonArray("cases").size() <= 128, "Case capacity exceeded");
        outer:
        for (var value : contract.getAsJsonArray("cases")) {
            var input = value.getAsJsonObject();
            for (int rebuild = 0; rebuild < 3; rebuild++) {
                try {
                    require(System.nanoTime() - started < 120_000_000_000L, "Component capture deadline exceeded");
                    rows.add(runCase(server, input, rebuild, contract.get("gameTimeArgument").getAsLong()));
                } catch (Exception | LinkageError failure) {
                    var problem = new JsonObject();
                    problem.addProperty("id", input.get("id").getAsString());
                    problem.addProperty("rebuild", rebuild);
                    problem.addProperty("errorType", failure.getClass().getSimpleName());
                    problem.addProperty("error", safeMessage(failure));
                    report.add("failure", problem);
                    report.addProperty("complete", false);
                    break outer;
                }
            }
        }
        report.addProperty("schema", "follow-core-reference-v1");
        report.addProperty("contract", "FOLLOW_CORE_V1");
        report.addProperty("contractSha256", sha(resource("contract.json")));
        report.addProperty("scope", contract.get("scope").getAsString());
        report.addProperty("serverThread", server.isSameThread());
        report.addProperty("naturalBrainVerified", false);
        report.addProperty("moveToTargetSinkVerified", false);
        report.addProperty("originalOnlineOwnerLookupVerified", false);
        report.addProperty("ownerAdapter", "real unregistered ServerPlayer; controlled getOwner reference and spectator/underwater flags");
        report.addProperty("maidAdapter", "real original Brain/swim/native navigation; reset wrapper delegates super; return-only teleport stubs have no memory effects");
        report.add("cases", rows);
        return report;
    }

    private static JsonObject runCase(MinecraftServer server, JsonObject spec, int rebuild, long gameTime) throws Exception {
        var level = server.overworld();
        Field randomField = Level.class.getDeclaredField("random");
        randomField.setAccessible(true);
        RandomSource previousWorldRandom = level.getRandom();
        var worldRandom = new RecordedRandom(spec.get("worldSeed").getAsLong());
        ProbeMaid maid = null;
        Owner owner = null;
        try {
            randomField.set(level, worldRandom);
            require(level.getRandom() == worldRandom, "World RNG adapter unavailable");
            String state = spec.get("ownerState").getAsString();
            ServerLevel ownerLevel = state.equals("other_level") ? server.getLevel(Level.NETHER) : level;
            require(ownerLevel != null, "Controlled alternate level unavailable");
            owner = new Owner(server, ownerLevel, new GameProfile(stableUuid(spec, "owner"), "TLMNHFollowOwner"));
            owner.spectator = state.equals("spectator");
            owner.underwater = spec.get("ownerUnderwater").getAsBoolean();
            owner.setPos(0.5 + spec.get("distance").getAsDouble(), 192, 0.5);
            if (state.equals("dead")) owner.setHealth(0);
            maid = new ProbeMaid(level);
            maid.setUUID(stableUuid(spec, "maid"));
            maid.setPos(0.5, 192, 0.5);
            maid.setNoAi(true);
            maid.setTame(true);
            maid.setOwnerUUID(owner.getUUID());
            maid.controlledOwner = state.equals("offline") ? null : owner;
            maid.referenceOwner = owner;
            maid.setHomeModeEnable(spec.get("home").getAsBoolean());
            maid.restrictTo(maid.blockPosition(), spec.get("radius").getAsInt());
            maid.teleportMode = spec.get("teleportMode").getAsString();
            maid.recordedNavigation = new RecordedNavigation(maid);
            String movement = spec.get("movement").getAsString();
            switch (movement) {
                case "free" -> {}
                case "sitting" -> maid.setInSittingPose(true);
                case "sleeping" -> maid.setSleepingPos(maid.blockPosition());
                case "passenger" -> require(maid.startRiding(owner, true), "Controlled passenger state unavailable");
                case "leashed" -> maid.setLeashedTo(owner, false);
                default -> throw new IllegalArgumentException("Unknown movement input");
            }
            maid.getSwimManager().setGoingToBreath(spec.get("breathing").getAsBoolean());
            maid.getSwimManager().setWantToSwim(true);
            maid.getSwimManager().setReadyToLand(true);
            if (spec.get("walkTarget").getAsString().equals("owner")) {
                maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(owner, true), 0.9F, 1));
            } else require(spec.get("walkTarget").getAsString().equals("none"), "Unsupported initial walk target");
            if (spec.get("seedOtherMemories").getAsBoolean()) {
                maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(owner, true));
                maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, owner);
                maid.getBrain().setMemory(InitEntities.TARGET_POS.get(), new EntityTracker(owner, true));
                maid.getBrain().setMemory(MemoryModuleType.PATH, new net.minecraft.world.level.pathfinder.Path(
                    List.of(new Node(0, 192, 0), new Node(1, 192, 0)), new BlockPos(1, 192, 0), true));
            }
            maid.recordedRandom = new RecordedRandom(spec.get("maidSeed").getAsLong());
            worldRandom.calls = new JsonArray();
            var input = copy(spec);
            input.add("actual", actualInput(maid));
            var before = memory(maid);
            var initialPosition = position(maid);
            var initialBox = box(maid.getBoundingBox());
            WalkTarget originalWalk = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            var task = new MaidFollowOwnerTask(0.5F, 2);
            boolean startedTask;
            String afterStart, afterStop;
            try (var platform = maid.teleportMode.startsWith("native_")
                ? new Platform(level, owner.blockPosition(), maid.blockPosition(), maid.teleportMode) : null) {
                maid.recording = true;
                startedTask = task.tryStart(level, maid, gameTime);
                afterStart = task.getStatus().name();
                if (startedTask) task.tickOrStop(level, maid, gameTime);
                afterStop = task.getStatus().name();
                maid.recording = false;
            }
            var normalised = memory(maid);
            normalised.addProperty("started", startedTask);
            normalised.addProperty("ownerQueries", maid.ownerQueries);
            normalised.addProperty("teleportCalls", maid.teleportReturns.size());
            normalised.addProperty("resetCalls", maid.resetCalls);
            normalised.addProperty("walkTargetRetained", originalWalk != null
                && originalWalk == maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
            var row = new JsonObject();
            row.addProperty("id", spec.get("id").getAsString());
            row.addProperty("rebuild", rebuild);
            row.add("input", input);
            row.add("before", before);
            row.add("initialMaidPosition", initialPosition);
            row.add("ownerPosition", position(owner));
            row.add("initialMaidBoundingBox", initialBox);
            row.add("normalised", normalised);
            row.add("callTrace", JsonParser.parseString(GSON.toJson(maid.calls)));
            row.add("teleportReturns", JsonParser.parseString(GSON.toJson(maid.teleportReturns)));
            row.add("teleportObservations", JsonParser.parseString(GSON.toJson(maid.teleportObservations)));
            row.add("maidRandomCalls", JsonParser.parseString(GSON.toJson(maid.recordedRandom.calls)));
            row.add("worldRandomCalls", JsonParser.parseString(GSON.toJson(worldRandom.calls)));
            row.addProperty("behaviourStatusAfterStart", afterStart);
            row.addProperty("behaviourStatusAfterTickOrStop", afterStop);
            row.addProperty("gameTimeArgument", gameTime);
            row.add("finalPosition", position(maid));
            row.add("finalBoundingBox", box(maid.getBoundingBox()));
            return row;
        } finally {
            try {
                if (maid != null) {
                    maid.recording = false;
                    try { maid.stopRiding(); }
                    finally { try { maid.dropLeash(false, false); } finally { maid.discard(); } }
                }
            } finally {
                try { if (owner != null) owner.discard(); }
                finally {
                    randomField.set(level, previousWorldRandom);
                    require(level.getRandom() == previousWorldRandom, "World RNG restoration failed");
                }
            }
        }
    }

    private static JsonObject actualInput(ProbeMaid maid) {
        var result = new JsonObject();
        LivingEntity owner = maid.controlledOwner;
        result.addProperty("radius", (double) maid.getRestrictRadius());
        result.addProperty("distanceSquared", owner == null ? null : maid.distanceToSqr(owner));
        result.addProperty("home", maid.isHomeModeEnable());
        result.addProperty("canBrainMove", maid.canBrainMoving());
        result.addProperty("breathing", maid.getSwimManager().isGoingToBreath());
        result.addProperty("ownerResolved", owner != null);
        result.addProperty("ownerSpectator", owner == null ? null : owner.isSpectator());
        result.addProperty("ownerDeadOrDying", owner == null ? null : owner.isDeadOrDying());
        result.addProperty("sameLevel", owner == null ? null : owner.level() == maid.level());
        result.addProperty("ownerUnderwater", owner == null ? null : owner.isUnderWater());
        return result;
    }

    private static JsonObject memory(ProbeMaid maid) {
        var brain = maid.getBrain();
        var result = new JsonObject();
        var walk = new JsonObject();
        var target = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        walk.addProperty("target", target == null ? "none" : trackerRole(maid, target.getTarget()));
        if (target == null) {
            walk.add("speed", JsonNull.INSTANCE);
            walk.add("closeEnough", JsonNull.INSTANCE);
        } else {
            walk.addProperty("speed", target.getSpeedModifier());
            walk.addProperty("closeEnough", target.getCloseEnoughDist());
        }
        result.add("walk", walk);
        result.addProperty("look", brain.getMemory(MemoryModuleType.LOOK_TARGET).map(value -> trackerRole(maid, value)).orElse("none"));
        result.addProperty("attack", brain.getMemory(MemoryModuleType.ATTACK_TARGET).map(value -> role(maid, value)).orElse("none"));
        result.addProperty("path", brain.getMemory(MemoryModuleType.PATH).isPresent() ? "present" : "none");
        result.addProperty("targetPos", brain.getMemory(InitEntities.TARGET_POS.get()).map(value -> trackerRole(maid, value)).orElse("none"));
        result.addProperty("breathing", maid.getSwimManager().isGoingToBreath());
        return result;
    }

    private static String trackerRole(ProbeMaid maid, PositionTracker tracker) {
        return tracker instanceof EntityTracker entity ? role(maid, entity.getEntity()) : "other";
    }

    private static String role(ProbeMaid maid, Entity entity) {
        return entity == null ? "none" : entity == maid.referenceOwner ? "owner" : "other";
    }

    private static UUID stableUuid(JsonObject spec, String role) {
        return UUID.nameUUIDFromBytes(("FOLLOW_CORE_V1/" + spec.get("id").getAsString() + "/" + role).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject position(Entity entity) {
        var result = new JsonObject();
        result.addProperty("x", entity.getX()); result.addProperty("y", entity.getY()); result.addProperty("z", entity.getZ());
        return result;
    }

    private static JsonArray box(AABB box) {
        var result = new JsonArray();
        for (double value : new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) result.add(value);
        return result;
    }

    private static JsonObject identity() throws Exception {
        Path manifestPath = Path.of(System.getProperty("tlmnh.follow.buildIdentity"));
        byte[] bytes = Files.readAllBytes(manifestPath);
        var manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(manifest.get("schema").getAsString().equals("follow-reference-build-v1")
            && manifest.get("status").getAsString().equals("BASE_LOCK_VERIFIED")
            && manifest.get("upstreamCommit").getAsString().equals(BASE), "Follow build identity was not verified");
        var base = manifest.getAsJsonObject("baseReferenceBuildIdentity");
        require(base.get("status").getAsString().equals("LOCK_VERIFIED") && Runtime.version().feature() == 17,
            "Verified Java 17 reference base required");
        require(manifest.get("contractSha256").getAsString().equals(sha(resource("contract.json")))
            && manifest.get("identityClassesSha256").getAsString().equals(sha(resource("identity-classes.json"))),
            "Follow resources differ from build manifest");
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        require(arguments.contains("-XX:+DisableAttachMechanism")
            && arguments.stream().filter(value -> value.startsWith("-javaagent:")).count() == 2
            && arguments.stream().noneMatch(value -> value.startsWith("-agentlib:") || value.startsWith("-agentpath:")),
            "Exactly base and follow read-only observers required");
        for (var entry : Map.of("runtimeVersion", System.getProperty("java.runtime.version"),
            "vendor", System.getProperty("java.vendor"), "vmName", System.getProperty("java.vm.name")).entrySet())
            require(base.getAsJsonObject("java").get(entry.getKey()).getAsString().equals(entry.getValue()), "Reference JDK identity differs");
        Class<?> agent = Class.forName(AGENT, false, ClassLoader.getSystemClassLoader());
        var startup = (Map<?, ?>) agent.getMethod("startup").invoke(null);
        require(startup.get("manifestSha256").equals(sha(bytes))
            && startup.get("agentSha256").equals(manifest.get("definitionAgentSha256").getAsString()), "Follow premain binding differs");
        var selected = JsonParser.parseString(new String(resource("identity-classes.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var classes = new JsonObject();
        for (var value : selected.getAsJsonArray("classes")) {
            String name = value.getAsString();
            Class<?> type = Class.forName(name, false, FollowReferenceProbe.class.getClassLoader());
            var definition = (Map<?, ?>) agent.getMethod("identity", Class.class).invoke(null, type);
            byte[] raw;
            try (var stream = type.getResourceAsStream('/' + name.replace('.', '/') + ".class")) {
                require(stream != null, "Selected class resource missing"); raw = stream.readAllBytes();
            }
            String resourceSha = sha(raw);
            if (manifest.getAsJsonObject("compiledClasses").has(name))
                require(resourceSha.equals(manifest.getAsJsonObject("compiledClasses").get(name).getAsString()), "Compiled class resource changed");
            var item = new JsonObject();
            item.addProperty("resourceSha256", resourceSha);
            item.addProperty("definitionSha256", definition.get("sha256").toString());
            item.addProperty("definitionCount", ((Number) definition.get("count")).intValue());
            item.addProperty("stage", definition.get("stage").toString());
            item.addProperty("loaderClass", definition.get("loaderClass").toString());
            classes.add(name, item);
        }
        var result = new JsonObject();
        result.addProperty("schema", "follow-reference-runtime-v1");
        result.addProperty("status", "VERIFIED_SELECTED_COMPONENT_CLASSES");
        result.addProperty("upstreamCommit", BASE);
        result.addProperty("buildIdentitySha256", sha(bytes));
        result.addProperty("baseReferenceBuildIdentitySha256", manifest.get("baseReferenceBuildIdentitySha256").getAsString());
        result.addProperty("agentSha256", startup.get("agentSha256").toString());
        result.addProperty("sourceTaskSha256", manifest.get("originalTaskSourceSha256").getAsString());
        result.add("classes", classes);
        return result;
    }

    private static byte[] resource(String name) throws Exception {
        try (var stream = FollowReferenceProbe.class.getResourceAsStream("/tlmnh-follow/" + name)) {
            require(stream != null, "Follow resource missing"); return stream.readAllBytes();
        }
    }

    private static void write(Path destination, JsonObject value) throws Exception {
        require(!Files.exists(destination), "Refusing to overwrite follow evidence");
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(temporary, destination);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonObject copy(JsonObject value) { return JsonParser.parseString(GSON.toJson(value)).getAsJsonObject(); }
    private static String safeMessage(Throwable failure) {
        String value = failure.getMessage();
        return value != null && !value.contains("\\") && !value.contains("/") && value.length() <= 200 ? value : "See local log; no private path exported";
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static final class Owner extends ServerPlayer {
        boolean spectator, underwater;
        Owner(MinecraftServer server, ServerLevel level, GameProfile profile) { super(server, level, profile); }
        @Override public boolean isSpectator() { return spectator; }
        @Override public boolean isUnderWater() { return underwater; }
    }

    private static final class ProbeMaid extends EntityMaid {
        LivingEntity controlledOwner, referenceOwner;
        RecordedNavigation recordedNavigation;
        RecordedRandom recordedRandom;
        String teleportMode;
        boolean recording;
        int ownerQueries, resetCalls;
        final JsonArray calls = new JsonArray(), teleportReturns = new JsonArray(), teleportObservations = new JsonArray();
        ProbeMaid(ServerLevel level) { super(level); }
        @Override public LivingEntity getOwner() {
            if (recording) {
                ownerQueries++;
                var call = new JsonObject(); call.addProperty("op", "resolveOwner"); call.addProperty("owner", controlledOwner == null ? "none" : "owner"); calls.add(call);
            }
            return controlledOwner;
        }
        @Override public RandomSource getRandom() { return recordedRandom == null ? super.getRandom() : recordedRandom; }
        @Override public MaidNavigationManager getNavigationManager() { return recordedNavigation == null ? super.getNavigationManager() : recordedNavigation; }
        @Override public boolean teleportToOwner(LivingEntity owner) {
            var before = memory(this);
            var beforePosition = FollowReferenceProbe.position(this);
            WalkTarget beforeWalk = getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            boolean nativeCall = teleportMode.startsWith("native_");
            boolean result = nativeCall ? super.teleportToOwner(owner) : teleportMode.equals("return_true");
            var call = new JsonObject(); call.addProperty("op", "teleport"); call.addProperty("result", result); call.addProperty("native", nativeCall); calls.add(call);
            teleportReturns.add(result);
            var observation = new JsonObject(); observation.addProperty("result", result); observation.addProperty("native", nativeCall);
            observation.add("before", before); observation.add("after", memory(this));
            observation.add("beforeMaidPosition", beforePosition);
            observation.add("afterMaidPosition", FollowReferenceProbe.position(this));
            observation.add("ownerPosition", FollowReferenceProbe.position(owner));
            observation.addProperty("walkTargetRetained", beforeWalk != null && beforeWalk == getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null));
            teleportObservations.add(observation);
            return result;
        }
    }

    private static final class RecordedNavigation extends MaidNavigationManager {
        private final ProbeMaid actor;
        RecordedNavigation(ProbeMaid maid) { super(maid); actor = maid; }
        @Override public void resetNavigation() {
            if (actor.recording) {
                actor.resetCalls++;
                var call = new JsonObject(); call.addProperty("op", "resetNavigation"); actor.calls.add(call);
            }
            super.resetNavigation();
        }
    }

    private static final class RecordedRandom implements RandomSource {
        private final RandomSource delegate;
        JsonArray calls = new JsonArray();
        RecordedRandom(long seed) { delegate = RandomSource.create(seed); }
        private void add(String method, Number value, Integer bound) {
            require(calls.size() < 256, "RNG call capacity exceeded");
            var call = new JsonObject(); call.addProperty("method", method); call.addProperty("value", value);
            if (bound != null) call.addProperty("bound", bound); calls.add(call);
        }
        @Override public RandomSource fork() { throw new IllegalStateException("Unobserved RNG fork outside component contract"); }
        @Override public PositionalRandomFactory forkPositional() { throw new IllegalStateException("Positional RNG outside component contract"); }
        @Override public void setSeed(long seed) { delegate.setSeed(seed); add("setSeed", seed, null); }
        @Override public int nextInt() { int value = delegate.nextInt(); add("nextInt", value, null); return value; }
        @Override public int nextInt(int bound) { int value = delegate.nextInt(bound); add("nextInt", value, bound); return value; }
        @Override public long nextLong() { long value = delegate.nextLong(); add("nextLong", value, null); return value; }
        @Override public boolean nextBoolean() { boolean value = delegate.nextBoolean(); add("nextBoolean", value ? 1 : 0, null); return value; }
        @Override public float nextFloat() { float value = delegate.nextFloat(); add("nextFloat", value, null); return value; }
        @Override public double nextDouble() { double value = delegate.nextDouble(); add("nextDouble", value, null); return value; }
        @Override public double nextGaussian() { double value = delegate.nextGaussian(); add("nextGaussian", value, null); return value; }
    }

    private static final class Platform implements AutoCloseable {
        private final ServerLevel level;
        private final Map<BlockPos, BlockState> before = new HashMap<>(), placed = new HashMap<>();
        Platform(ServerLevel level, BlockPos owner, BlockPos maidFeet, String mode) {
            this.level = level;
            try {
                require(Set.of("native_safe", "native_blocked", "native_head_blocked").contains(mode), "Unsupported native teleport fixture");
                for (int x = owner.getX() - 5; x <= owner.getX() + 5; x++) for (int z = owner.getZ() - 5; z <= owner.getZ() + 5; z++)
                    level.getChunk(x >> 4, z >> 4);
                require(level.getEntities(null, new AABB(owner.getX() - 5, 190, owner.getZ() - 5, owner.getX() + 6, 197, owner.getZ() + 6)).isEmpty(), "Native teleport fixture occupied");
                require(level.getEntities(null, new AABB(maidFeet.getX() - 1, 191, maidFeet.getZ() - 1, maidFeet.getX() + 2, 196, maidFeet.getZ() + 2)).isEmpty(), "Initial maid fixture occupied");
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 3; dy++) {
                    var pos = maidFeet.offset(dx, dy, dz);
                    require(level.isEmptyBlock(pos) && level.getBlockEntity(pos) == null, "Initial maid space must be pure air");
                }
                for (int x = owner.getX() - 4; x <= owner.getX() + 4; x++) for (int z = owner.getZ() - 4; z <= owner.getZ() + 4; z++)
                    for (int y = 191; y <= 195; y++) {
                        var pos = new BlockPos(x, y, z);
                        require(level.isEmptyBlock(pos) && level.getBlockEntity(pos) == null, "Native teleport fixture must be pure air");
                        before.put(pos, level.getBlockState(pos));
                    }
                var support = maidFeet.below();
                require(level.isEmptyBlock(support) && level.getBlockEntity(support) == null, "Initial maid support must be pure air before fixture");
                before.put(support, level.getBlockState(support));
                for (var pos : before.keySet()) if (pos.getY() == 191
                    || mode.equals("native_blocked") && pos.getY() >= 192 && pos.getY() <= 194
                    || mode.equals("native_head_blocked") && pos.getY() >= 193 && pos.getY() <= 194) {
                    require(level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3), "Native platform write failed");
                    placed.put(pos, Blocks.STONE.defaultBlockState());
                }
            } catch (RuntimeException | LinkageError failure) {
                close(); throw failure;
            }
        }
        @Override public void close() {
            RuntimeException first = null;
            for (var entry : placed.entrySet()) {
                try {
                    require(level.getBlockState(entry.getKey()).equals(entry.getValue()), "Fixture changed before restoration");
                    require(level.setBlock(entry.getKey(), before.get(entry.getKey()), 3), "Native platform restoration failed");
                } catch (RuntimeException failure) {
                    if (first == null) first = failure; else first.addSuppressed(failure);
                }
            }
            placed.clear();
            if (first != null) throw first;
        }
    }
}
