package com.tianluoqaq.tlmnh.reference;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Actual registered original maid, untouched Brain and world ticks; observation and scripted owner input only */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class FollowNaturalReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static volatile Session active;
    private static volatile boolean queued;
    private static boolean ioFailed;

    private FollowNaturalReferenceProbe() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.followNaturalProbe") || event.phase != TickEvent.Phase.END) return;
        var minecraft = Minecraft.getInstance();
        var current = active;
        if (current != null && (minecraft.getSingleplayerServer() != current.server || minecraft.level == null
            || current.expired())) current.requestAbort("client_world_changed_or_wall_deadline");
        if (ioFailed) return;
        try {
            if (root == null) {
                root = minecraft.gameDirectory.toPath().resolve("tlmnh-follow-natural-reference");
                for (String child : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(child));
                TouhouLittleMaid.LOGGER.info("TLMNH_FOLLOW_NATURAL_REFERENCE_READY contract=FOLLOW_NATURAL_WATER_O_V1");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var requests = new ArrayList<Path>(); stream.forEach(requests::add); requests.sort(Path::compareTo);
                if (!requests.isEmpty()) handle(requests.get(0));
            }
        } catch (Exception | LinkageError failure) {
            ioFailed = true;
            if (current != null) current.requestAbort("control_io_failed");
            logFailure("control_io", failure);
        }
    }

    private static void handle(Path path) throws Exception {
        String name = path.getFileName().toString();
        var response = new JsonObject();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= 16384, "invalid_request");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            String command = request.get("command").getAsString();
            var current = active;
            switch (command) {
                case "status" -> {
                    response.addProperty("contract", "FOLLOW_NATURAL_WATER_O_V1");
                    response.addProperty("queued", queued); response.addProperty("active", current != null);
                    response.addProperty("stage", current == null ? "idle" : current.status);
                    response.addProperty("worldLoaded", minecraft.getSingleplayerServer() != null);
                    response.addProperty("clientSpectator", minecraft.player != null && minecraft.player.isSpectator());
                    response.addProperty("clientInvulnerable", minecraft.player != null && minecraft.player.getAbilities().invulnerable);
                    if (minecraft.player != null) {
                        response.addProperty("clientSelectedSlot", minecraft.player.getInventory().selected);
                        response.add("clientMainHand", stackState(minecraft.player.getMainHandItem()));
                        response.add("clientOffHand", stackState(minecraft.player.getOffhandItem()));
                    }
                }
                case "cancel" -> { if (current != null) current.requestAbort("explicit_cancel"); response.addProperty("cancelRequested", current != null); }
                case "lab_create" -> { require(!queued && current == null, "capture_active"); response = ReferenceScene.createLab(); }
                case "lab_open" -> { require(!queued && current == null, "capture_active"); response = ReferenceScene.openLab(); }
                case "capture_natural" -> {
                    require(!queued && current == null, "capture_active");
                    require(request.get("repetitions").getAsInt() == 3 && request.get("repetitions").getAsDouble() == 3,
                        "three_fresh_rebuilds_required");
                    var server = minecraft.getSingleplayerServer();
                    require(server != null && minecraft.level != null && minecraft.player != null
                        && minecraft.player.isSpectator() && minecraft.player.getAbilities().invulnerable,
                        "protected_integrated_spectator_required");
                    UUID owner = minecraft.player.getUUID();
                    minecraft.options.pauseOnLostFocus = false; minecraft.setScreen(null);
                    queued = true; Files.delete(path);
                    server.execute(() -> begin(server, owner, name)); return;
                }
                case "shutdown" -> {
                    require(!queued, "capture_queued");
                    if (current != null) {
                        current.server.execute(() -> {
                            current.finish("shutdown_requested", null);
                            Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                        });
                    } else minecraft.stop();
                }
                default -> throw new IllegalArgumentException("unsupported_command");
            }
            response.addProperty("status", "complete");
        } catch (Exception | LinkageError failure) {
            logFailure("control_request_" + name, failure);
            response.addProperty("status", "inconclusive"); response.addProperty("errorType", failure.getClass().getSimpleName());
            response.addProperty("reason", safeReason(failure));
        }
        response.addProperty("request", name); write(root.resolve("responses").resolve(name), response); Files.deleteIfExists(path);
    }

    private static void begin(MinecraftServer server, UUID ownerId, String name) {
        Session session = null;
        try {
            require(server.isSameThread() && active == null, "actual_exclusive_server_thread_required");
            var owner = server.getPlayerList().getPlayer(ownerId);
            require(owner != null, "real_logged_in_owner_missing");
            session = new Session(server, owner, name); active = session; session.start();
        } catch (Exception | LinkageError failure) {
            if (session != null) session.finish("setup_failed", failure);
            else {
                TouhouLittleMaid.LOGGER.error("TLMNH_FOLLOW_NATURAL_REFERENCE_SETUP_FAILED request=" + name, failure);
                var response = new JsonObject(); response.addProperty("status", "inconclusive");
                response.addProperty("errorType", failure.getClass().getSimpleName()); response.addProperty("request", name);
                response.addProperty("reason", safeReason(failure));
                response.add("serverPreflight", serverPreflight(server, ownerId));
                try { write(root.resolve("responses").resolve(name), response); } catch (Exception writing) { ioFailed = true; }
            }
        } finally { queued = false; }
    }

    /** Failure evidence only: no mode, inventory, position, protection or world changes */
    private static JsonObject serverPreflight(MinecraftServer server, UUID ownerId) {
        var result = new JsonObject();
        try {
            result.addProperty("serverThread", server.isSameThread());
            result.addProperty("worldName", server.getWorldData().getLevelName());
            result.addProperty("overworldDimension", server.overworld().dimension().location().toString());
            result.addProperty("playerCount", server.getPlayerList().getPlayerCount());
            var player = server.getPlayerList().getPlayer(ownerId);
            result.addProperty("ownerPresent", player != null);
            if (player != null) {
                result.addProperty("ownerDimension", player.serverLevel().dimension().location().toString());
                result.addProperty("ownerInOverworld", player.serverLevel() == server.overworld());
                result.addProperty("mode", player.gameMode.getGameModeForPlayer().getName());
                result.addProperty("spectator", player.isSpectator());
                result.addProperty("abilityInvulnerable", player.getAbilities().invulnerable);
                result.addProperty("entityInvulnerable", player.isInvulnerable());
                result.addProperty("cameraSelf", player.getCamera() == player);
                result.addProperty("passenger", player.isPassenger());
                result.addProperty("sleeping", player.isSleeping());
                result.add("mainHand", handPreflight(player.getMainHandItem()));
                result.add("offHand", handPreflight(player.getOffhandItem()));
                result.addProperty("selectedSlot", player.getInventory().selected);
                var empty = new JsonArray();
                for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) if (player.getInventory().getItem(slot).isEmpty()) empty.add(slot);
                result.add("emptyHotbarSlots", empty);
            }
        } catch (RuntimeException | LinkageError failure) {
            result.addProperty("diagnosticErrorType", failure.getClass().getSimpleName());
        }
        return result;
    }

    private static JsonObject handPreflight(net.minecraft.world.item.ItemStack stack) {
        var result = new JsonObject();
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        result.addProperty("registryId", id == null ? null : id.toString());
        result.addProperty("count", stack.getCount()); result.addProperty("empty", stack.isEmpty()); return result;
    }

    @SubscribeEvent
    public static void levelTick(TickEvent.LevelTickEvent event) {
        var current = active;
        if (current == null || event.level != current.level || event.level.isClientSide) return;
        try {
            require(current.server.isSameThread(), "level_event_not_server_thread");
            if (event.phase == TickEvent.Phase.START) current.beforeTick(); else current.afterTick();
        } catch (Exception | LinkageError failure) { current.finish("natural_tick_observation_failed", failure); }
    }

    @SubscribeEvent
    public static void livingTick(LivingEvent.LivingTickEvent event) {
        var current = active;
        if (current != null && current.maid != null && event.getEntity() == current.maid && !current.level.isClientSide) {
            try {
                require(current.server.isSameThread() && current.frame != null, "maid_tick_outside_observed_level_bracket");
                current.livingTicks++;
            } catch (RuntimeException | LinkageError failure) { current.finish("living_tick_bracket_failed", failure); }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void loggingOut(PlayerEvent.PlayerLoggedOutEvent event) {
        var current = active;
        // Forge PlayerList.remove posts this before save(player); restore the real owner before that save.
        if (current != null && event.getEntity() == current.owner) current.finish("real_owner_logging_out", null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void stopping(ServerStoppingEvent event) {
        var current = active;
        if (current != null && current.server == event.getServer()) current.finish("server_stopping", null);
    }

    private static final class Session {
        final MinecraftServer server;
        final ServerLevel level;
        final ServerPlayer owner;
        final UUID ownerId;
        final String name;
        final long started = System.nanoTime();
        final JsonObject contract, report = new JsonObject();
        final JsonArray runs = new JsonArray();
        final PlayerState playerState;
        final WorldState worldState;
        final int samplingSelectedSlot;
        final Map<net.minecraft.world.level.pathfinder.Path, String> paths = new IdentityHashMap<>();
        final Map<BehaviorControl<?>, Integer> behaviorIds = new IdentityHashMap<>();
        final List<BehaviorControl<?>> behaviors = new ArrayList<>();
        volatile String status = "setup";
        volatile boolean abortQueued;
        Platform platform;
        EntityMaid maid;
        Brain<?> brain;
        JsonObject row, frame;
        JsonArray frames;
        int caseIndex, rebuild, warmup, frameIndex, totalTicks, livingTicks, beforeEntityTick;
        long beforeGameTime;
        boolean finished;

        Session(MinecraftServer server, ServerPlayer owner, String name) throws Exception {
            this.server = server; this.owner = owner; this.name = name; ownerId = owner.getUUID(); level = server.overworld();
            contract = JsonParser.parseString(new String(FollowNaturalReferenceIdentity.resource("contract.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            require(contract.get("schema").getAsString().equals("follow-natural-contract-v1")
                && contract.get("contract").getAsString().equals("FOLLOW_NATURAL_WATER_O_V1")
                && contract.get("rebuilds").getAsInt() == 3, "unsupported_natural_contract");
            var slotPolicy = contract.getAsJsonObject("ownerSlotPreparation");
            require(slotPolicy != null && slotPolicy.get("allowSelectExistingEmptyHotbar").getAsBoolean()
                && !slotPolicy.get("moveOrDeleteStacks").getAsBoolean() && slotPolicy.get("offhandMustBeEmpty").getAsBoolean()
                && slotPolicy.get("sampleHandsMustBeEmpty").getAsBoolean() && slotPolicy.get("restoreOriginalSelectedSlot").getAsBoolean(),
                "unsupported_owner_slot_preparation_contract");
            require(server.getWorldData().getLevelName().equals("TLMNH-Reference-Lab") && owner.serverLevel() == level
                && owner.isSpectator() && owner.getAbilities().invulnerable && owner.getCamera() == owner
                && !owner.isPassenger() && !owner.isSleeping(),
                "dedicated_protected_reference_lab_required");
            require(server.getPlayerList().getPlayerCount() == 1 && owner.getOffhandItem().isEmpty(),
                "single_real_owner_with_empty_offhand_required");
            require(Inventory.isHotbarSlot(owner.getInventory().selected), "owner_selected_slot_invalid");
            int emptySlot = owner.getMainHandItem().isEmpty() ? owner.getInventory().selected : -1;
            for (int slot = 0; emptySlot < 0 && slot < Inventory.getSelectionSize(); slot++)
                if (owner.getInventory().getItem(slot).isEmpty()) emptySlot = slot;
            require(emptySlot >= 0, "no_existing_empty_hotbar_slot");
            samplingSelectedSlot = emptySlot;
            playerState = new PlayerState(owner); worldState = new WorldState(level);
        }

        void start() throws Exception {
            var mods = Set.copyOf(ModList.get().getMods().stream().map(info -> info.getModId()).toList());
            require(mods.equals(Set.of("minecraft", "forge", "touhou_little_maid"))
                && ModList.get().getModContainerById("forge").orElseThrow().getModInfo().getVersion().toString().equals("47.2.0"),
                "minimal_original_forge_47_2_0_required");
            report.addProperty("schema", "follow-natural-reference-v1"); report.addProperty("contract", "FOLLOW_NATURAL_WATER_O_V1");
            report.addProperty("contractSha256", sha(FollowNaturalReferenceIdentity.resource("contract.json")));
            report.addProperty("scope", contract.get("scope").getAsString());
            report.addProperty("worldNatural", true); report.addProperty("completeOriginalBrain", true);
            report.addProperty("realLoggedInOwner", true); report.addProperty("portEquivalenceVerified", false);
            report.addProperty("statisticalAdmission", false); report.addProperty("worldRngReplaced", false);
            report.addProperty("worldRngReset", false); report.addProperty("maidRngReset", false);
            report.addProperty("randomStateFullyControlled", false);
            report.addProperty("transientBehaviorCallsObserved", false); report.addProperty("teleportMethodCallsObserved", false);
            report.addProperty("worldRandomClass", level.getRandom().getClass().getName());
            report.add("identity", FollowNaturalReferenceIdentity.capture()); report.add("runs", runs);
            report.add("ownerBefore", playerState.json()); report.add("worldBefore", worldState.json());
            platform = new Platform(level, contract, report);
            require(owner.getInventory().selected == playerState.selectedSlot && owner.getOffhandItem().isEmpty()
                && owner.getInventory().getItem(samplingSelectedSlot).isEmpty(), "sampling_slot_preparation_state_changed");
            playerState.verifyStacks(owner);
            var preparation = new JsonObject();
            preparation.addProperty("originalSelectedSlot", playerState.selectedSlot);
            preparation.addProperty("samplingSelectedSlot", samplingSelectedSlot);
            preparation.add("actualBefore", selectionState(owner));
            selectSlot(owner, samplingSelectedSlot);
            preparation.addProperty("syncPacket", ClientboundSetCarriedItemPacket.class.getName());
            preparation.add("actualAfter", selectionState(owner));
            report.add("slotPreparation", preparation);
            worldState.configure(server);
            require(owner.setGameMode(GameType.CREATIVE) || owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE,
                "owner_creative_transition_rejected");
            var abilities = owner.getAbilities(); abilities.flying = true; abilities.mayfly = true; abilities.invulnerable = true;
            owner.setInvulnerable(true); owner.setDeltaMovement(Vec3.ZERO); owner.fallDistance = 0; owner.onUpdateAbilities();
            guardOwner(); beginWarmup();
        }

        boolean expired() { return System.nanoTime() - started > contract.get("maximumSeconds").getAsLong() * 1_000_000_000L; }

        void requestAbort(String reason) {
            if (abortQueued || finished) return;
            abortQueued = true; server.execute(() -> { if (active == this) finish(reason, null); });
        }

        JsonObject spec() { return contract.getAsJsonArray("cases").get(caseIndex).getAsJsonObject(); }
        Vec3 origin() { return vector(contract.getAsJsonArray("origin")); }

        void guardOwner() {
            require(server.getPlayerList().getPlayer(ownerId) == owner && server.getPlayerList().getPlayerCount() == 1
                && owner.serverLevel() == level && owner.connection != null && owner.connection.getPlayer() == owner
                && owner.connection.connection.isConnected() && !owner.hasDisconnected() && owner.isAlive()
                && !owner.isChangingDimension() && owner.getCamera() == owner && !owner.isPassenger() && !owner.isSleeping(),
                "real_owner_identity_or_connection_changed");
            require(owner.gameMode.getGameModeForPlayer() == GameType.CREATIVE && !owner.isSpectator()
                && owner.getAbilities().invulnerable && owner.isInvulnerable(), "owner_protection_or_mode_changed");
            require(owner.getInventory().selected == samplingSelectedSlot && owner.getMainHandItem().isEmpty()
                && owner.getOffhandItem().isEmpty(), "sampling_selected_slot_or_empty_hands_changed");
        }

        void beginWarmup() {
            platform.prepareTerrain(spec().get("terrain").getAsString());
            status = "warmup:" + spec().get("id").getAsString() + ":" + rebuild;
            warmup = 0; frameIndex = 0; frame = null; paths.clear(); behaviors.clear(); behaviorIds.clear();
            applyOwner(plannedOwner(0));
        }

        Vec3 plannedOwner(int index) {
            var keys = spec().getAsJsonArray("ownerKeyframes");
            JsonObject previous = keys.get(0).getAsJsonObject();
            for (int i = 1; i < keys.size(); i++) {
                var next = keys.get(i).getAsJsonObject();
                if (index <= next.get("frame").getAsInt()) {
                    int start = previous.get("frame").getAsInt(), end = next.get("frame").getAsInt();
                    double fraction = Math.max(0, Math.min(1, (double) (index - start) / (end - start)));
                    var a = vector(previous.getAsJsonArray("position")); var b = vector(next.getAsJsonArray("position"));
                    return origin().add(a.add(b.subtract(a).scale(fraction)));
                }
                previous = next;
            }
            return origin().add(vector(previous.getAsJsonArray("position")));
        }

        JsonObject applyOwner(Vec3 position) {
            var result = new JsonObject(); result.add("plannedPosition", vector(position)); result.add("before", entity(owner));
            boolean invoke = owner.position().distanceToSqr(position) > 1.0e-12;
            result.addProperty("methodInvoked", invoke);
            if (invoke) {
                result.addProperty("method", "ServerGamePacketListenerImpl.teleport");
                result.add("actualArguments", vector(position));
                owner.connection.teleport(position.x, position.y, position.z, 0, 0);
            }
            result.add("actualAfterCall", entity(owner)); result.add("connectionAfterCall", connection()); return result;
        }

        void beforeTick() throws Exception {
            if (finished) return;
            require(!expired() && ++totalTicks <= contract.get("maximumTicks").getAsInt(), "natural_capture_deadline");
            guardOwner();
            if (maid == null) { applyOwner(plannedOwner(0)); return; }
            require(frame == null, "unclosed_level_tick_bracket"); guardMaid();
            frame = new JsonObject(); frames.add(frame); livingTicks = 0;
            frame.addProperty("index", frameIndex); frame.add("ownerInput", applyOwner(plannedOwner(frameIndex)));
            beforeEntityTick = maid.tickCount; beforeGameTime = level.getGameTime();
            frame.add("before", snapshot());
        }

        void afterTick() throws Exception {
            if (finished) return;
            guardOwner();
            if (maid == null) {
                warmup++;
                require(warmup <= contract.get("maximumWarmupTicks").getAsInt(), "owner_ticket_warmup_timeout");
                BlockPos spawn = BlockPos.containing(origin().add(vector(contract.getAsJsonArray("maidSpawn"))));
                if (warmup >= contract.get("warmupTicks").getAsInt() && level.isPositionEntityTicking(spawn)
                    && read(ServerGamePacketListenerImpl.class, "awaitingPositionFromClient", owner.connection) == null) spawn();
                return;
            }
            require(frame != null, "missing_level_start_observation");
            frame.addProperty("livingTickEvents", livingTicks); frame.add("after", snapshot()); guardMaid();
            require(maid.tickCount == beforeEntityTick + 1 && livingTicks == 1 && level.getGameTime() == beforeGameTime + 1,
                "natural_entity_tick_not_observed_once");
            double tolerance = contract.get("ownerTolerance").getAsDouble();
            frame.addProperty("actualOwnerErrorSquared", owner.position().distanceToSqr(plannedOwner(frameIndex)));
            require(owner.position().distanceToSqr(plannedOwner(frameIndex)) <= tolerance * tolerance, "actual_owner_trajectory_drift");
            frame = null; frameIndex++;
            if (frameIndex == spec().get("ticks").getAsInt()) {
                row.add("terrainAfter", platform.verifyTerrain());
                row.addProperty("complete", true); removeMaid();
                if (++rebuild == 3) { rebuild = 0; caseIndex++; }
                if (caseIndex == contract.getAsJsonArray("cases").size()) finish(null, null); else beginWarmup();
            }
        }

        void spawn() throws Exception {
            require(maid == null, "previous_natural_actor_not_removed");
            platform.assertEmpty(owner);
            maid = new EntityMaid(level);
            require(maid.getClass() == EntityMaid.class && !maid.isNoAi(), "unmodified_original_ai_actor_required");
            maid.setOwnerUUID(ownerId); maid.setTame(true); maid.setInSittingPose(false); maid.setHomeModeEnable(false);
            maid.setTask(TaskManager.getIdleTask()); maid.setSchedule(MaidSchedule.ALL);
            Vec3 at = origin().add(vector(contract.getAsJsonArray("maidSpawn")));
            maid.moveTo(at.x, at.y, at.z, 0, 0);
            require(level.addFreshEntity(maid), "natural_maid_registration_rejected"); guardMaid(); brain = maid.getBrain();
            row = new JsonObject(); row.addProperty("id", spec().get("id").getAsString()); row.addProperty("rebuild", rebuild);
            row.addProperty("complete", false); row.addProperty("cleanupComplete", false); row.add("input", copy(spec()));
            frames = new JsonArray(); row.add("frames", frames); runs.add(row);
            row.addProperty("maidUuid", maid.getUUID().toString()); row.addProperty("ownerUuid", ownerId.toString());
            row.addProperty("samplingSelectedSlot", samplingSelectedSlot);
            row.addProperty("maidRandomClass", maid.getRandom().getClass().getName());
            row.addProperty("worldRngAndSensorPhaseUncontrolled", true); row.addProperty("warmupBeforeSpawnTicks", warmup);
            row.add("terrainBefore", platform.verifyTerrain());
            row.add("brainRegistration", registration()); row.add("spawnState", snapshot());
            status = "capturing:" + row.get("id").getAsString() + ":" + rebuild;
            TouhouLittleMaid.LOGGER.info("TLMNH_FOLLOW_NATURAL_RUN_BEGIN case={} rebuild={}", row.get("id").getAsString(), rebuild);
        }

        void guardMaid() {
            require(maid != null && maid.getClass() == EntityMaid.class && !maid.isNoAi() && maid.isAlive() && !maid.isRemoved()
                && level.getEntity(maid.getUUID()) == maid && level.getEntity(maid.getId()) == maid && maid.level() == level
                && level.isPositionEntityTicking(maid.blockPosition()), "registered_natural_maid_identity_or_ticking_changed");
            require(maid.getOwner() == owner && ownerId.equals(maid.getOwnerUUID()) && maid.isTame()
                && maid.getSchedule() == MaidSchedule.ALL && maid.getTask().getUid().equals(com.github.tartaricacid.touhoulittlemaid.entity.task.TaskIdle.UID)
                && !maid.isHomeModeEnable() && !maid.isMaidInSittingPose() && !maid.isPassenger() && !maid.isLeashed()
                && !maid.isSleeping() && !maid.guiOpening && maid.getRestrictRadius() == 8, "natural_follow_configuration_changed");
            require(brain == null || maid.getBrain() == brain, "original_brain_instance_changed");
            require(platform.contains(maid.position()), "natural_actor_left_safe_platform_volume");
        }

        void removeMaid() {
            if (maid == null) return;
            EntityMaid removed = maid;
            removed.discard();
            require(removed.isRemoved() && level.getEntity(removed.getUUID()) != removed && level.getEntity(removed.getId()) != removed,
                "natural_actor_removal_not_observed");
            if (row != null) row.addProperty("cleanupComplete", true);
            maid = null; brain = null;
        }

        void finish(String reason, Throwable failure) {
            if (finished) return;
            finished = true; status = "cleanup";
            if (failure != null) logFailure("observation_" + name, failure);
            Throwable cleanupFailure = null;
            var cleanupStages = new JsonArray();
            report.add("cleanupStages", cleanupStages);
            if (reason != null) report.addProperty("reason", reason);
            cleanupFailure = cleanupStage(cleanupStages, "maid", this::removeMaid, cleanupFailure);
            cleanupFailure = cleanupStage(cleanupStages, "platform", () -> {
                if (platform != null) platform.close();
                else if (report.has("platform")) require(report.getAsJsonObject("platform").get("cleanupComplete").getAsBoolean(),
                    "platform_constructor_cleanup_not_verified");
            }, cleanupFailure);
            cleanupFailure = cleanupStage(cleanupStages, "world", () -> worldState.restore(server), cleanupFailure);
            cleanupFailure = cleanupStage(cleanupStages, "owner", () -> playerState.restore(owner), cleanupFailure);
            cleanupFailure = cleanupStage(cleanupStages, "owner_actual_verification", () -> {
                report.add("ownerAfterCleanup", entity(owner));
                report.add("ownerAfterCleanupState", new PlayerState(owner).json());
                report.add("ownerRestoration", playerState.verify(owner));
            }, cleanupFailure);
            cleanupFailure = cleanupStage(cleanupStages, "actor_removal_verification", () -> {
                report.addProperty("remainingActorRegistered", maid != null && level.getEntity(maid.getUUID()) == maid);
                report.addProperty("remainingActorUuid", maid == null ? null : maid.getUUID().toString());
                require(maid == null || level.getEntity(maid.getUUID()) != maid, "remaining_actor_registered_after_cleanup");
            }, cleanupFailure);
            int expectedRuns = contract.getAsJsonArray("cases").size() * contract.get("rebuilds").getAsInt();
            int expectedFrames = 0, observedFrames = 0;
            for (var scene : contract.getAsJsonArray("cases"))
                expectedFrames += scene.getAsJsonObject().get("ticks").getAsInt() * contract.get("rebuilds").getAsInt();
            boolean allRowsComplete = true;
            for (var run : runs) {
                var sample = run.getAsJsonObject();
                observedFrames += sample.getAsJsonArray("frames").size();
                allRowsComplete &= sample.get("complete").getAsBoolean() && sample.get("cleanupComplete").getAsBoolean()
                    && sample.getAsJsonArray("frames").size() == sample.getAsJsonObject("input").get("ticks").getAsInt();
            }
            report.addProperty("expectedRuns", expectedRuns); report.addProperty("expectedFrames", expectedFrames);
            report.addProperty("observedFrames", observedFrames);
            boolean observationComplete = reason == null && failure == null && runs.size() == expectedRuns
                && observedFrames == expectedFrames && allRowsComplete;
            report.addProperty("observationComplete", observationComplete);
            report.addProperty("cleanupComplete", cleanupFailure == null);
            report.addProperty("complete", observationComplete && cleanupFailure == null);
            report.addProperty("totalObservedLevelTicks", totalTicks);
            report.add("observationFailure", failureInfo(failure));
            report.add("cleanupFailure", failureInfo(cleanupFailure));
            Throwable problem = failure != null ? failure : cleanupFailure;
            if (problem != null) { report.addProperty("errorType", problem.getClass().getSimpleName()); report.addProperty("failureReason", safeReason(problem)); }
            var response = new JsonObject(); response.addProperty("request", name);
            try {
                byte[] json = (GSON.toJson(report) + "\n").getBytes(StandardCharsets.UTF_8);
                require(json.length <= 67_108_864, "natural_capture_json_capacity");
                String filename = name.substring(0, name.length() - 5) + ".json.gz";
                Path file = root.resolve("captures").resolve(filename), temporary = file.resolveSibling(filename + ".tmp");
                require(!Files.exists(file), "natural_capture_exists");
                try (var gzip = new GZIPOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) { gzip.write(json); }
                Files.move(temporary, file);
                byte[] compressed = Files.readAllBytes(file);
                response.addProperty("capture", "captures/" + filename); response.addProperty("captureSha256", sha(compressed));
                response.addProperty("compressedBytes", compressed.length); response.addProperty("jsonBytes", json.length);
                response.addProperty("status", report.get("complete").getAsBoolean() ? "complete" : "inconclusive");
                response.addProperty("verdict", "OBSERVATION_ONLY_NOT_AI_BENCHMARK_PASS");
                write(root.resolve("responses").resolve(name), response);
                TouhouLittleMaid.LOGGER.info("TLMNH_FOLLOW_NATURAL_REFERENCE_CAPTURE {}", response);
            } catch (Exception | LinkageError writing) {
                ioFailed = true; logFailure("artifact_write_" + name, writing);
            } finally { status = "finished"; if (active == this) active = null; }
        }

        JsonObject connection() {
            try {
                var value = new JsonObject();
                value.addProperty("connected", owner.connection.connection.isConnected());
                value.addProperty("awaitingTeleport", (Integer) read(ServerGamePacketListenerImpl.class, "awaitingTeleport", owner.connection));
                Vec3 awaiting = (Vec3) read(ServerGamePacketListenerImpl.class, "awaitingPositionFromClient", owner.connection);
                value.add("awaitingPosition", awaiting == null ? JsonNull.INSTANCE : vector(awaiting)); return value;
            } catch (Exception failure) { throw new IllegalStateException("connection_observation_unavailable", failure); }
        }

        // State readers are defined below; they never tick AI, mutate memory or evaluate visibility predicates.
        JsonObject snapshot() throws Exception { return snapshotState(this); }
        JsonObject registration() throws Exception { return registerState(this); }
    }

    private static final class Platform implements AutoCloseable {
        final ServerLevel level;
        final int minX, maxX, minZ, maxZ, footY;
        final List<BlockPos> placed = new ArrayList<>();
        final JsonObject report = new JsonObject();
        boolean closed;
        final Map<BlockPos, BlockState> terrain = new LinkedHashMap<>();
        final JsonArray terrainRecords = new JsonArray();
        JsonObject terrainRecord;
        String terrainKind;

        Platform(ServerLevel level, JsonObject contract, JsonObject parent) {
            this.level = level; parent.add("platform", report); report.add("terrains", terrainRecords);
            var origin = vector(contract.getAsJsonArray("origin"));
            int radius = contract.get("platformRadius").getAsInt();
            require(radius == 32 && origin.y == Math.floor(origin.y), "bounded_integer_platform_required");
            footY = (int) origin.y; minX = (int) origin.x - radius; maxX = (int) origin.x + radius;
            minZ = (int) origin.z - radius; maxZ = (int) origin.z + radius;
            report.add("origin", vector(origin)); report.addProperty("radius", radius);
            report.addProperty("creationComplete", false); report.addProperty("cleanupComplete", false);
            report.addProperty("forcedChunksAdded", false);
            try {
                require(level.getChunkSource().getGenerator() instanceof FlatLevelSource, "original_void_generator_required");
                int loaded = 0;
                for (int x = minX >> 4; x <= maxX >> 4; x++) for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                    level.getChunk(x, z); loaded++;
                }
                report.addProperty("synchronousChunksRequested", loaded);
                for (int x : new int[] {minX, maxX}) for (int z : new int[] {minZ, maxZ})
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++)
                        require(level.getBlockState(new BlockPos(x, y, z)).equals(Blocks.AIR.defaultBlockState()), "nonvoid_corner_column");
                assertEmpty(null);
                int checked = 0;
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                    for (int y = footY - 2; y <= footY + 3; y++) {
                        BlockPos at = new BlockPos(x, y, z);
                        require(air(at), "platform_requires_original_air_without_block_entities"); checked++;
                    }
                report.addProperty("preflightAirCells", checked);
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                    BlockPos at = new BlockPos(x, footY - 1, z); require(air(at), "platform_source_changed"); placed.add(at);
                    require(level.setBlock(at, Blocks.STONE.defaultBlockState(), 2)
                        && level.getBlockState(at).equals(Blocks.STONE.defaultBlockState()), "platform_placement_failed");
                }
                report.addProperty("placedBlocks", placed.size()); report.addProperty("creationComplete", true);
            } catch (RuntimeException | LinkageError failure) {
                try { close(); } catch (RuntimeException | LinkageError cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void prepareTerrain(String kind) {
            require(kind.equals("submerged_channel") || kind.equals("submerged_low_tunnel"), "unsupported_water_terrain");
            clearTerrain();
            terrainKind = kind; terrainRecord = new JsonObject(); terrainRecords.add(terrainRecord);
            terrainRecord.addProperty("kind", kind); terrainRecord.addProperty("cleanupComplete", false);
            int ox = (minX + maxX) / 2, oz = (minZ + maxZ) / 2;
            // 水源下方为原有整块平台，四壁高于水面；全部改动在已核对的空气体积内
            for (int x = -2; x <= 16; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++) {
                boolean wall = x == -2 || x == 16 || z == -2 || z == 2;
                boolean roof = kind.equals("submerged_low_tunnel") && x >= 4 && x <= 7 && y >= 1;
                if (!wall && !roof && y == 3) continue;
                var at = new BlockPos(ox + x, footY + y, oz + z);
                var state = wall || roof ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState();
                require(air(at), "water_terrain_requires_original_air");
                terrain.put(at, state);
                require(level.setBlock(at, state, 2) && level.getBlockState(at).equals(state), "water_terrain_placement_failed");
            }
            terrainRecord.add("initial", verifyTerrain());
        }

        JsonObject verifyTerrain() {
            var evidence = new JsonObject(); int water = 0, glass = 0;
            var encoded = new StringBuilder();
            for (var entry : terrain.entrySet()) {
                var at = entry.getKey();
                require(level.getBlockEntity(at) == null && level.getBlockState(at).equals(entry.getValue()),
                    "owned_water_terrain_changed");
                if (entry.getValue().is(Blocks.WATER)) water++; else glass++;
                encoded.append(at.toShortString()).append('=').append(entry.getValue()).append(';');
            }
            evidence.addProperty("kind", terrainKind); evidence.addProperty("waterCells", water);
            evidence.addProperty("glassCells", glass); evidence.addProperty("cells", terrain.size());
            try {
                evidence.addProperty("sha256", sha(encoded.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failure) {
                throw new IllegalStateException("water_terrain_digest_unavailable", failure);
            }
            return evidence;
        }

        void clearTerrain() {
            if (terrain.isEmpty()) return;
            verifyTerrain();
            // 先移水源，最后移外壁，避免清理中向展台外放流
            for (var block : List.of(Blocks.WATER, Blocks.GLASS)) for (var entry : terrain.entrySet())
                if (entry.getValue().is(block)) {
                    require(level.setBlock(entry.getKey(), Blocks.AIR.defaultBlockState(), 2), "water_terrain_restore_rejected");
                    require(air(entry.getKey()), "water_terrain_air_not_restored");
                }
            terrainRecord.addProperty("restoredBlocks", terrain.size()); terrainRecord.addProperty("cleanupComplete", true);
            terrain.clear();
        }

        boolean air(BlockPos at) { return level.getBlockState(at).equals(Blocks.AIR.defaultBlockState()) && level.getBlockEntity(at) == null; }
        boolean contains(Vec3 at) { return at.x >= minX + .4 && at.x <= maxX + .6 && at.z >= minZ + .4 && at.z <= maxZ + .6 && at.y >= footY - .2 && at.y <= footY + 5; }
        void assertEmpty(Entity permitted) {
            for (var entity : level.getEntities((Entity) null, new AABB(minX, footY - 2, minZ, maxX + 1, footY + 6, maxZ + 1)))
                require(entity == permitted, "unexpected_entity_in_natural_platform");
        }

        @Override public void close() {
            if (closed) return;
            clearTerrain();
            Throwable failure = null; int restored = 0;
            for (int i = placed.size() - 1; i >= 0; i--) {
                var at = placed.get(i);
                try {
                    require(level.hasChunk(at.getX() >> 4, at.getZ() >> 4), "cleanup_chunk_unloaded");
                    if (!air(at)) {
                        require(level.getBlockState(at).equals(Blocks.STONE.defaultBlockState()) && level.getBlockEntity(at) == null,
                            "owned_platform_changed_refusing_overwrite");
                        require(level.setBlock(at, Blocks.AIR.defaultBlockState(), 2), "platform_restore_rejected");
                    }
                    require(air(at), "original_air_not_restored"); restored++;
                } catch (RuntimeException | LinkageError problem) { failure = collect(failure, problem); }
            }
            closed = failure == null; report.addProperty("restoredBlocks", restored); report.addProperty("cleanupComplete", closed);
            if (failure != null) throw new IllegalStateException("platform_cleanup_failed", failure);
        }
    }

    private static final class PlayerState {
        final ServerLevel level;
        final Vec3 position, movement;
        final float yaw, pitch, health, fallDistance, flySpeed, walkSpeed;
        final boolean invulnerable, abilityInvulnerable, flying, mayfly, instabuild, mayBuild;
        final GameType mode;
        final int selectedSlot;
        final List<ItemStack> hotbar = new ArrayList<>();
        final ItemStack offhand;
        final JsonArray observedHotbar;
        final JsonObject observedMainHand, observedOffHand;

        PlayerState(ServerPlayer player) {
            level = player.serverLevel(); position = player.position(); movement = player.getDeltaMovement();
            yaw = player.getYRot(); pitch = player.getXRot(); health = player.getHealth(); fallDistance = player.fallDistance;
            invulnerable = player.isInvulnerable(); mode = player.gameMode.getGameModeForPlayer();
            var abilities = player.getAbilities(); abilityInvulnerable = abilities.invulnerable; flying = abilities.flying;
            mayfly = abilities.mayfly; instabuild = abilities.instabuild; mayBuild = abilities.mayBuild;
            flySpeed = abilities.getFlyingSpeed(); walkSpeed = abilities.getWalkingSpeed();
            selectedSlot = player.getInventory().selected;
            require(Inventory.isHotbarSlot(selectedSlot), "owner_selected_slot_invalid");
            require(Inventory.getSelectionSize() == 9, "unexpected_hotbar_size");
            observedHotbar = hotbarState(player);
            observedMainHand = stackState(player.getMainHandItem()); observedOffHand = stackState(player.getOffhandItem());
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) hotbar.add(player.getInventory().getItem(slot).copy());
            offhand = player.getOffhandItem().copy();
        }

        JsonObject json() {
            var result = new JsonObject(); result.add("position", vector(position)); result.addProperty("mode", mode.getName());
            result.addProperty("dimension", level.dimension().location().toString());
            result.add("movement", vector(movement)); result.addProperty("yaw", yaw); result.addProperty("pitch", pitch);
            result.addProperty("fallDistance", fallDistance);
            result.addProperty("health", health); result.addProperty("entityInvulnerable", invulnerable);
            result.addProperty("abilityInvulnerable", abilityInvulnerable); result.addProperty("flying", flying);
            result.addProperty("mayfly", mayfly); result.addProperty("instabuild", instabuild); result.addProperty("mayBuild", mayBuild);
            result.addProperty("flyingSpeed", flySpeed); result.addProperty("walkingSpeed", walkSpeed);
            result.addProperty("selectedSlot", selectedSlot); result.add("mainHand", observedMainHand);
            result.add("offHand", observedOffHand); result.add("hotbar", observedHotbar); return result;
        }

        void restore(ServerPlayer player) {
            Throwable failure = null;
            failure = attempt(() -> player.setGameMode(mode), failure);
            failure = attempt(() -> selectSlot(player, selectedSlot), failure);
            failure = attempt(() -> player.teleportTo(level, position.x, position.y, position.z, yaw, pitch), failure);
            failure = attempt(() -> {
                var abilities = player.getAbilities(); abilities.invulnerable = abilityInvulnerable; abilities.flying = flying;
                abilities.mayfly = mayfly; abilities.instabuild = instabuild; abilities.mayBuild = mayBuild;
                abilities.setFlyingSpeed(flySpeed); abilities.setWalkingSpeed(walkSpeed); player.onUpdateAbilities();
            }, failure);
            failure = attempt(() -> { player.setInvulnerable(invulnerable); player.setHealth(health); player.setDeltaMovement(movement); player.fallDistance = fallDistance; }, failure);
            failure = attempt(() -> verify(player), failure);
            if (failure != null) throw new IllegalStateException("owner_restore_failed", failure);
        }

        JsonObject verify(ServerPlayer player) {
            var a = player.getAbilities();
            require(player.serverLevel() == level && player.gameMode.getGameModeForPlayer() == mode && player.isSpectator()
                && player.position().distanceToSqr(position) <= 1.0e-12 && player.getHealth() == health
                && player.getYRot() == yaw && player.getXRot() == pitch && player.getDeltaMovement().equals(movement)
                && player.fallDistance == fallDistance
                && player.isInvulnerable() == invulnerable && a.invulnerable == abilityInvulnerable && a.invulnerable
                && a.flying == flying && a.mayfly == mayfly && a.instabuild == instabuild && a.mayBuild == mayBuild
                && a.getFlyingSpeed() == flySpeed && a.getWalkingSpeed() == walkSpeed, "owner_restore_actual_state_differs");
            require(player.getInventory().selected == selectedSlot, "original_selected_slot_not_restored");
            verifyStacks(player);
            var result = new JsonObject(); result.addProperty("serverStateVerified", true);
            result.addProperty("clientAcknowledgementVerified", false); result.addProperty("playerDatPersistenceVerified", false); return result;
        }

        void verifyStacks(ServerPlayer player) {
            for (int slot = 0; slot < hotbar.size(); slot++) require(ItemStack.matches(hotbar.get(slot), player.getInventory().getItem(slot)),
                "original_hotbar_stack_changed");
            require(ItemStack.matches(offhand, player.getOffhandItem()), "original_offhand_stack_changed");
        }
    }

    private static final class WorldState {
        final ServerLevel level;
        final ServerLevelData data;
        final boolean mobSpawning, daylight, weather, raining, thundering;
        final int rainTime, thunderTime, clearTime;
        final long dayTime;
        final float rain, oldRain, thunder, oldThunder;

        WorldState(ServerLevel level) {
            this.level = level; require(level.getLevelData() instanceof ServerLevelData, "original_server_level_data_required");
            data = (ServerLevelData) level.getLevelData();
            var rules = level.getGameRules(); mobSpawning = rules.getBoolean(GameRules.RULE_DOMOBSPAWNING);
            daylight = rules.getBoolean(GameRules.RULE_DAYLIGHT); weather = rules.getBoolean(GameRules.RULE_WEATHER_CYCLE);
            raining = data.isRaining(); thundering = data.isThundering(); rainTime = data.getRainTime();
            thunderTime = data.getThunderTime(); clearTime = data.getClearWeatherTime(); dayTime = level.getDayTime();
            rain = level.rainLevel; oldRain = level.oRainLevel; thunder = level.thunderLevel; oldThunder = level.oThunderLevel;
        }

        JsonObject json() {
            var result = new JsonObject(); result.addProperty("mobSpawning", mobSpawning); result.addProperty("daylight", daylight);
            result.addProperty("weatherCycle", weather); result.addProperty("dayTime", dayTime); result.addProperty("raining", raining);
            result.addProperty("thundering", thundering); result.addProperty("gameTimeRewound", false); return result;
        }

        void configure(MinecraftServer server) {
            var rules = level.getGameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server); rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false); level.setRainLevel(0); level.setThunderLevel(0);
        }

        void restore(MinecraftServer server) {
            Throwable failure = null;
            failure = attempt(() -> level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(mobSpawning, server), failure);
            failure = attempt(() -> level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight, server), failure);
            failure = attempt(() -> level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(weather, server), failure);
            failure = attempt(() -> level.setDayTime(dayTime), failure);
            failure = attempt(() -> { data.setRaining(raining); data.setThundering(thundering); data.setRainTime(rainTime); data.setThunderTime(thunderTime); data.setClearWeatherTime(clearTime); }, failure);
            failure = attempt(() -> { level.rainLevel = rain; level.oRainLevel = oldRain; level.thunderLevel = thunder; level.oThunderLevel = oldThunder; }, failure);
            failure = attempt(() -> require(level.getDayTime() == dayTime && data.isRaining() == raining && data.isThundering() == thundering
                && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) == mobSpawning
                && level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) == daylight
                && level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) == weather, "world_restore_actual_state_differs"), failure);
            if (failure != null) throw new IllegalStateException("world_restore_failed", failure);
        }
    }

    private static JsonObject registerState(Session session) throws Exception {
        var result = new JsonObject();
        var entries = new ArrayList<JsonObject>();
        var controls = new IdentityHashMap<JsonObject, BehaviorControl<?>>();
        var registry = (Map<?, ?>) read(Brain.class, "availableBehaviorsByPriority", session.brain);
        require(registry.size() <= 128, "behavior_priority_capacity");
        for (var priority : registry.entrySet()) for (var activity : ((Map<?, ?>) priority.getValue()).entrySet())
            for (var item : (Set<?>) activity.getValue()) {
                require(item instanceof BehaviorControl<?>, "unexpected_behavior_type");
                var value = new JsonObject(); value.addProperty("priority", ((Number) priority.getKey()).intValue());
                value.addProperty("activity", activity.getKey().toString()); value.addProperty("class", item.getClass().getName());
                entries.add(value); controls.put(value, (BehaviorControl<?>) item);
            }
        entries.sort(Comparator.comparingInt((JsonObject value) -> value.get("priority").getAsInt())
            .thenComparing(value -> value.get("activity").getAsString()).thenComparing(value -> value.get("class").getAsString()));
        // IDs are per-run object identities. No behavior or actual registry collection is replaced or reordered.
        var registration = new JsonArray();
        for (var value : entries) {
            var behavior = controls.get(value);
            Integer id = session.behaviorIds.get(behavior);
            if (id == null) { id = session.behaviors.size(); session.behaviors.add(behavior); session.behaviorIds.put(behavior, id); }
            var actual = copy(value); actual.addProperty("id", id); registration.add(actual);
        }
        require(session.behaviors.size() <= 128, "behavior_instance_capacity"); result.add("behaviors", registration);
        result.add("sensors", sensors(session));
        result.addProperty("originalActivitiesRemoved", false); result.addProperty("guiOpening", session.maid.guiOpening);
        return result;
    }

    private static JsonArray sensors(Session session) throws Exception {
        var result = new JsonArray();
        var values = (Map<?, ?>) read(Brain.class, "sensors", session.brain);
        var ordered = new ArrayList<>(values.values()); ordered.sort(Comparator.comparing(value -> value.getClass().getName()));
        require(ordered.size() <= 32, "sensor_capacity");
        for (Object sensor : ordered) {
            var value = new JsonObject(); value.addProperty("class", sensor.getClass().getName());
            value.addProperty("scanRate", ((Number) read(Sensor.class, "scanRate", sensor)).intValue());
            value.addProperty("timeToTick", ((Number) read(Sensor.class, "timeToTick", sensor)).longValue()); result.add(value);
        }
        return result;
    }

    private static JsonObject snapshotState(Session s) throws Exception {
        var maid = s.maid;
        var result = new JsonObject(); result.addProperty("gameTime", s.level.getGameTime()); result.addProperty("dayTime", s.level.getDayTime());
        result.add("maid", entity(maid)); result.add("owner", entity(s.owner)); result.add("ownerConnection", s.connection());
        result.addProperty("ownerResolvedSamePlayerListObject", maid.getOwner() == s.owner && s.server.getPlayerList().getPlayer(s.ownerId) == s.owner);
        result.addProperty("worldRegisteredByUuid", s.level.getEntity(maid.getUUID()) == maid);
        result.addProperty("worldRegisteredById", s.level.getEntity(maid.getId()) == maid);
        result.addProperty("positionEntityTicking", s.level.isPositionEntityTicking(maid.blockPosition()));
        result.addProperty("noAi", maid.isNoAi()); result.addProperty("guiOpening", maid.guiOpening);
        result.addProperty("task", maid.getTask().getUid().toString());
        result.addProperty("schedule", maid.getSchedule().name()); result.addProperty("home", maid.isHomeModeEnable());
        result.addProperty("radius", maid.getRestrictRadius()); result.addProperty("canBrainMoving", maid.canBrainMoving());
        result.addProperty("sitting", maid.isMaidInSittingPose()); result.addProperty("sleeping", maid.isSleeping());
        result.addProperty("passenger", maid.isPassenger()); result.addProperty("leashed", maid.isLeashed());
        result.addProperty("usingItem", maid.isUsingItem()); result.addProperty("useItemClass", maid.getUseItem().isEmpty() ? null : maid.getUseItem().getItem().getClass().getName());
        result.addProperty("model", maid.getModelId()); result.addProperty("width", maid.getBbWidth()); result.addProperty("height", maid.getBbHeight());
        result.addProperty("stepHeight", maid.getStepHeight()); result.addProperty("speed", maid.getSpeed());
        result.addProperty("xxa", maid.xxa); result.addProperty("yya", maid.yya); result.addProperty("zza", maid.zza);
        result.add("movementAttribute", attribute(maid, Attributes.MOVEMENT_SPEED)); result.add("followRangeAttribute", attribute(maid, Attributes.FOLLOW_RANGE));
        var control = maid.getMoveControl(); var move = new JsonObject(); move.addProperty("class", control.getClass().getName());
        move.addProperty("hasWanted", control.hasWanted()); move.addProperty("speedModifier", control.getSpeedModifier());
        move.add("wanted", vector(new Vec3(control.getWantedX(), control.getWantedY(), control.getWantedZ()))); result.add("moveControl", move);
        var nav = maid.getNavigation(); var navigation = new JsonObject(); navigation.addProperty("class", nav.getClass().getName());
        navigation.addProperty("done", nav.isDone()); navigation.addProperty("stuck", nav.isStuck()); navigation.add("path", path(s, nav.getPath()));
        result.add("navigation", navigation);
        var activeActivities = new JsonArray(); maid.getBrain().getActiveActivities().stream().map(Object::toString).sorted().forEach(activeActivities::add);
        result.add("activeActivities", activeActivities);
        var states = new JsonArray(); for (var behavior : s.behaviors) states.add(behavior.getStatus().name()); result.add("behaviorStates", states);
        var running = new JsonArray();
        for (var behavior : maid.getBrain().getRunningBehaviors()) {
            Integer id = s.behaviorIds.get(behavior); require(id != null, "running_behavior_not_in_original_registry"); running.add(id);
        }
        result.add("runningBehaviorIds", running); result.add("sensors", sensors(s));
        var memory = new JsonObject();
        var entries = new ArrayList<>(maid.getBrain().getMemories().entrySet());
        entries.sort(Comparator.comparing(entry -> memoryName(entry.getKey())));
        require(entries.size() <= 128, "memory_module_capacity");
        for (var entry : entries) {
            var value = new JsonObject(); Optional<? extends ExpirableValue<?>> optional = entry.getValue();
            value.addProperty("present", optional.isPresent());
            if (optional.isPresent()) {
                var raw = optional.get(); value.addProperty("timeToLive", raw.getTimeToLive());
                value.addProperty("canExpire", raw.canExpire()); value.add("value", memoryValue(s, raw.getValue(), 0));
            }
            memory.add(memoryName(entry.getKey()), value);
        }
        result.add("memory", memory); return result;
    }

    private static JsonObject attribute(EntityMaid maid, Attribute attribute) {
        var actual = maid.getAttribute(attribute); require(actual != null, "required_original_attribute_missing");
        var value = new JsonObject(); value.addProperty("base", actual.getBaseValue()); value.addProperty("value", actual.getValue());
        var modifiers = new JsonArray(); var list = new ArrayList<>(actual.getModifiers()); list.sort(Comparator.comparing(item -> item.getId().toString()));
        require(list.size() <= 32, "attribute_modifier_capacity");
        for (var item : list) { var modifier = new JsonObject(); modifier.addProperty("uuid", item.getId().toString()); modifier.addProperty("name", item.getName()); modifier.addProperty("amount", item.getAmount()); modifier.addProperty("operation", item.getOperation().name()); modifiers.add(modifier); }
        value.add("modifiers", modifiers); return value;
    }

    private static JsonElement memoryValue(Session s, Object raw, int depth) throws Exception {
        require(depth <= 6, "memory_recursion_capacity");
        if (raw == null) return JsonNull.INSTANCE;
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof String) return GSON.toJsonTree(raw);
        if (raw instanceof MaidEdibleBlockAction action) {
            var value = new JsonObject(); value.addProperty("kind", "maid_edible_block_action");
            value.addProperty("class", action.getDeclaringClass().getName()); value.addProperty("name", action.name()); return value;
        }
        if (raw instanceof Entity entity) { var value = entity(entity); value.addProperty("role", entity == s.maid ? "maid" : entity == s.owner ? "owner" : "other"); return value; }
        if (raw instanceof WalkTarget walk) { var value = new JsonObject(); value.addProperty("kind", "walk"); value.add("tracker", memoryValue(s, walk.getTarget(), depth + 1)); value.addProperty("speed", walk.getSpeedModifier()); value.addProperty("closeEnough", walk.getCloseEnoughDist()); return value; }
        if (raw instanceof PositionTracker tracker) {
            var value = new JsonObject(); value.addProperty("kind", "tracker"); value.addProperty("class", raw.getClass().getName());
            value.add("block", block(tracker.currentBlockPosition())); value.add("position", vector(tracker.currentPosition()));
            if (tracker instanceof EntityTracker entityTracker) value.add("entity", memoryValue(s, entityTracker.getEntity(), depth + 1)); return value;
        }
        if (raw instanceof net.minecraft.world.level.pathfinder.Path path) return path(s, path);
        if (raw instanceof BlockPos at) { var value = new JsonObject(); value.addProperty("kind", "block"); value.add("block", block(at)); return value; }
        if (raw instanceof GlobalPos at) { var value = new JsonObject(); value.addProperty("kind", "global_block"); value.addProperty("dimension", at.dimension().location().toString()); value.add("block", block(at.pos())); return value; }
        if (raw instanceof NearestVisibleLivingEntities) {
            var value = new JsonObject(); value.addProperty("kind", "visible_entity_candidates");
            value.addProperty("visibilityNotForced", true);
            value.add("nearbyEntities", memoryValue(s, read(NearestVisibleLivingEntities.class, "nearbyEntities", raw), depth + 1)); return value;
        }
        if (raw instanceof Collection<?> collection) {
            require(collection.size() <= 128, "memory_collection_capacity"); var value = new JsonObject();
            value.addProperty("kind", raw instanceof Set<?> ? "set" : "list"); var items = new JsonArray();
            for (var item : collection) items.add(memoryValue(s, item, depth + 1)); value.add("items", items); return value;
        }
        throw new IllegalStateException("unsupported_memory_value_" + raw.getClass().getSimpleName());
    }

    private static JsonElement path(Session s, net.minecraft.world.level.pathfinder.Path path) {
        if (path == null) return JsonNull.INSTANCE;
        String id = s.paths.get(path);
        if (id == null) { require(s.paths.size() < 256, "natural_path_identity_capacity"); id = "path" + s.paths.size(); s.paths.put(path, id); }
        var result = new JsonObject(); result.addProperty("kind", "path"); result.addProperty("id", id);
        result.addProperty("canReach", path.canReach()); result.addProperty("nodeCount", path.getNodeCount()); result.addProperty("nextNodeIndex", path.getNextNodeIndex());
        result.addProperty("done", path.isDone()); result.add("target", block(path.getTarget()));
        require(path.getNodeCount() <= 256, "natural_path_node_capacity"); var nodes = new JsonArray();
        var types = new JsonArray(); var costs = new JsonArray();
        for (int i = 0; i < path.getNodeCount(); i++) {
            var node = path.getNode(i); nodes.add(block(node.asBlockPos()));
            types.add(node.type.name()); costs.add(node.costMalus);
        }
        result.add("nodes", nodes); result.add("nodeTypes", types); result.add("nodeCosts", costs); return result;
    }

    private static String memoryName(MemoryModuleType<?> type) {
        var name = ForgeRegistries.MEMORY_MODULE_TYPES.getKey(type); require(name != null, "unregistered_memory_type"); return name.toString();
    }

    private static JsonObject entity(Entity entity) {
        var value = new JsonObject(); value.addProperty("uuid", entity.getUUID().toString()); value.addProperty("entityId", entity.getId());
        value.addProperty("class", entity.getClass().getName()); value.addProperty("tickCount", entity.tickCount);
        value.add("position", vector(entity.position())); value.add("block", block(entity.blockPosition()));
        value.add("movement", vector(entity.getDeltaMovement())); value.addProperty("alive", entity.isAlive()); value.addProperty("removed", entity.isRemoved());
        value.addProperty("onGround", entity.onGround()); value.addProperty("fallDistance", entity.fallDistance);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) value.addProperty("health", living.getHealth());
        if (entity instanceof ServerPlayer player) {
            value.addProperty("mode", player.gameMode.getGameModeForPlayer().getName()); value.addProperty("spectator", player.isSpectator());
            value.addProperty("abilityInvulnerable", player.getAbilities().invulnerable);
            value.addProperty("selectedSlot", player.getInventory().selected);
            value.add("mainHand", stackState(player.getMainHandItem())); value.add("offHand", stackState(player.getOffhandItem()));
        }
        return value;
    }

    private static void selectSlot(ServerPlayer player, int slot) {
        require(Inventory.isHotbarSlot(slot) && player.connection != null, "valid_selected_slot_and_real_connection_required");
        player.getInventory().selected = slot;
        player.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    private static JsonObject selectionState(ServerPlayer player) {
        var result = new JsonObject(); result.addProperty("selectedSlot", player.getInventory().selected);
        result.add("mainHand", stackState(player.getMainHandItem())); result.add("offHand", stackState(player.getOffhandItem())); return result;
    }

    private static JsonArray hotbarState(ServerPlayer player) {
        var result = new JsonArray();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            var value = new JsonObject(); value.addProperty("slot", slot); value.add("stack", stackState(player.getInventory().getItem(slot))); result.add(value);
        }
        return result;
    }

    private static JsonObject stackState(ItemStack stack) {
        var result = handPreflight(stack);
        try { result.addProperty("nbtSha256", sha(stack.save(new CompoundTag()).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("item_snapshot_sha_unavailable", failure); }
        return result;
    }

    private static JsonArray vector(Vec3 value) { require(Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z), "nonfinite_observation"); var out = new JsonArray(); out.add(value.x); out.add(value.y); out.add(value.z); return out; }
    private static Vec3 vector(JsonArray value) { require(value.size() == 3, "vector_requires_three_coordinates"); return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble()); }
    private static JsonArray block(BlockPos value) { var out = new JsonArray(); out.add(value.getX()); out.add(value.getY()); out.add(value.getZ()); return out; }
    private static JsonObject copy(JsonObject value) { return JsonParser.parseString(GSON.toJson(value)).getAsJsonObject(); }
    private static Object read(Class<?> owner, String name, Object instance) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
    private static Throwable attempt(Runnable action, Throwable previous) { try { action.run(); } catch (RuntimeException | LinkageError failure) { return collect(previous, failure); } return previous; }
    private static Throwable cleanupStage(JsonArray stages, String stage, Runnable action, Throwable previous) {
        var result = new JsonObject(); result.addProperty("stage", stage);
        try { action.run(); result.addProperty("success", true); }
        catch (RuntimeException | LinkageError failure) {
            result.addProperty("success", false); result.add("failure", failureInfo(failure));
            logFailure("cleanup_" + stage, failure); previous = collect(previous, failure);
        } finally { stages.add(result); }
        return previous;
    }
    private static JsonObject failureInfo(Throwable failure) {
        if (failure == null) return null;
        var result = new JsonObject(); result.addProperty("errorType", failure.getClass().getSimpleName());
        result.addProperty("reason", safeReason(failure)); result.addProperty("suppressedCount", failure.getSuppressed().length); return result;
    }
    private static void logFailure(String stage, Throwable failure) {
        try { TouhouLittleMaid.LOGGER.error("TLMNH_FOLLOW_NATURAL_REFERENCE_FAILURE stage=" + stage, failure); }
        catch (RuntimeException | LinkageError loggingFailure) {
            System.err.println("TLMNH_FOLLOW_NATURAL_REFERENCE_LOG_FALLBACK stage=" + stage);
            failure.printStackTrace(System.err); loggingFailure.printStackTrace(System.err);
        }
    }
    private static Throwable collect(Throwable previous, Throwable next) { if (previous == null) return next; if (previous != next) previous.addSuppressed(next); return previous; }
    private static String sha(byte[] bytes) throws Exception { return FollowNaturalReferenceIdentity.sha(bytes); }
    private static String safeReason(Throwable failure) { String message = failure.getMessage(); return message != null && message.matches("[A-Za-z0-9_$]{1,160}") ? message : "inspect_retained_local_log"; }
    private static void write(Path file, JsonObject value) throws Exception { require(!Files.exists(file), "response_already_exists"); Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); Files.move(temporary, file); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
