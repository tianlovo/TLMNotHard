package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidWorldSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryLedger;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.development.telemetry.MaidServerLoadProbe;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 单selector内部串行容量曲线；内存连接只提供原生owner，不冒充真实多人长时证据 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ServerLoadProfileTests {

    private ServerLoadProfileTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 18000, batch = "server_load_serial")
    public static void serialNativeCapacityAndActualRequestFairness(GameTestHelper helper) throws Exception {
        helper.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Explicit packaged development mode required");
        helper.assertTrue(
            "1".equals(System.getProperty("horizonqa.turbo", "1")),
            "Native capacity requires turbo=1 including periodic native saves");
        var attempt = new Attempt(helper);
        helper.afterTest(attempt::close);
        attempt.prepare();
        helper.onEachTick("serial native server-load profile", attempt::tick);
        helper.succeedWhen(() -> attempt.complete);
    }

    public static final class Attempt {

        final GameTestHelper helper;
        final WorldServer world;
        final MinecraftServer server = MinecraftServer.getServer();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final List<EntityMaid> actors = new ArrayList<>();
        final List<EntityMaid> allActors = new ArrayList<>();
        final List<BlockBefore> blocks = new ArrayList<>();
        final JsonObject report = new JsonObject();
        final JsonArray stages = new JsonArray(), fairnessFrames = new JsonArray(), inputs = new JsonArray();
        final int[] counts = { 0, 1, 10, 30, 1, 10, 30, 0 };
        final int x, y, z;
        final boolean oldAnimals, oldGift;
        final String oldSpawning, oldDaylight;
        final long oldTime, started;
        final int warmup = 400, sampled = 1200;
        EntityPlayerMP owner;
        EmbeddedChannel channel;
        MaidServerLoadProbe probe;
        int stage, age, fairnessAge, drainAge;
        boolean fairness, draining, complete, closed;

        Attempt(GameTestHelper helper) {
            this.helper = helper;
            world = helper.getWorld();
            var origin = helper.absolute(0, 0, 0);
            x = origin.x() + 64;
            y = origin.y();
            z = origin.z() + 64;
            oldAnimals = server.getCanSpawnAnimals();
            oldGift = MaidAcquisitionConfig.giveInitialSlab;
            oldSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            oldDaylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            oldTime = world.getWorldTime();
            started = world.getTotalWorldTime();
        }

        void prepare() throws Exception {
            helper.assertTrue(
                server.getConfigurationManager().playerEntityList.isEmpty(),
                "Isolated CPU capacity run requires no other players");
            helper.assertTrue(
                world.loadedEntityList.stream()
                    .noneMatch(value -> value instanceof EntityMaid),
                "Native capacity baseline requires no unrelated maid; none will be deleted");
            chunks.forceChunksStrict(world, x, y, z, x + 79, y + 8, z + 47);
            server.setCanSpawnAnimals(true);
            MaidAcquisitionConfig.giveInitialSlab = false;
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.setWorldTime(6000);
            for (int bx = x; bx < x + 80; bx++) for (int bz = z; bz < z + 48; bz++) for (int by = y; by < y + 6; by++) {
                helper.assertTrue(
                    world.isAirBlock(bx, by, bz) && world.getTileEntity(bx, by, bz) == null,
                    "Unknown native capacity platform successor");
                if (by == y) {
                    blocks.add(new BlockBefore(bx, by, bz));
                    helper.assertTrue(
                        world.setBlock(bx, by, bz, Blocks.stone, 0, 2) && world.getBlock(bx, by, bz) == Blocks.stone,
                        "Native capacity floor placement failed");
                }
            }
            owner = new EntityPlayerMP(
                server,
                world,
                new GameProfile(UUID.randomUUID(), "LoadOwner"),
                new ItemInWorldManager(world));
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            var handler = new NetHandlerPlayServer(server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            moveOwner(0);
            server.getConfigurationManager()
                .initializeConnectionToPlayer(manager, owner, handler);
            owner.setGameType(GameType.CREATIVE);
            owner.capabilities.allowFlying = owner.capabilities.isFlying = owner.capabilities.disableDamage = true;
            moveOwner(0);
            helper.assertTrue(
                MaidSpawnChecks.present(owner) && server.getConfigurationManager().playerEntityList.contains(owner)
                    && world.playerEntities.contains(owner),
                "Full isolated native owner initialization failed");
            report.addProperty("schema", "server-load-profile-v1");
            report.addProperty(
                "world",
                world.getWorldInfo()
                    .getWorldName());
            report.addProperty(
                "scope",
                "Single fresh JVM, single internally serial selector, complete F/N roots and native movement; controlled memory owner is not real multiplayer");
            report.addProperty(
                "cpuIsolationRequired",
                "No other game JVM, build, reference capture or external benchmark during the capacity run");
            report.addProperty("warmupTicksPerStage", warmup);
            report.addProperty("sampleTicksPerStage", sampled);
            report.addProperty("fairnessWindowTicks", 240);
            report.addProperty("serverCanSpawnAnimalsBefore", oldAnimals);
            report.addProperty("periodicNativeSaveTicks", 900);
            report.addProperty(
                "ownerTrajectory",
                "Native EntityPlayerMP.setPosition on a 21.6-block horizontal shuttle, .18 block/tick, reversal every120 ticks; normal maid attributes, navigation and collisions");
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(MaidServerLoadProbe.class, EntityMaid.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity
                    .capture(ServerLoadProfileTests.class, Attempt.class, SleepAttributePortSnapshot.class));
            report.add("spawnInputArtifact", DevelopmentArtifactIdentity.capture(IsolatedPlayerChannel.class));
            probe = MaidServerLoadProbe.start(System.getProperty("horizonqa.reportDir"), "native-capacity", world);
            beginStage();
            save("running");
        }

        void beginStage() {
            age = 0;
            moveOwner(0);
            var profile = stage < 4 ? MaidBrainProfile.FAITHFUL_TLM : MaidBrainProfile.ENHANCED;
            spawnActors(counts[stage], profile, false);
            probe.phase(stageLabel() + "-warmup", actors.size(), false);
        }

        String stageLabel() {
            return stage == 0 ? "zero-before" : stage == 7 ? "zero-after" : (stage < 4 ? "F-" : "N-") + counts[stage];
        }

        void spawnActors(int count, MaidBrainProfile profile, boolean instrumented) {
            helper.assertTrue(actors.isEmpty(), "Previous owned roster was not fully removed");
            for (int i = 0; i < count; i++) {
                var maid = new EntityMaid(world);
                helper.assertTrue(maid.bindTo(owner.getUniqueID()), "Native load owner binding failed");
                maid.setTask(MaidTask.IDLE);
                maid.setSchedule(MaidSchedule.ALL);
                maid.setSitting(false);
                maid.setPickup(false);
                maid.setHunger(100);
                maid.setPosition(x + 22 + (i % 6) * 1.2, y + 1, z + 18 + (i / 6) * 1.2);
                actors.add(maid);
                allActors.add(maid);
                helper.assertTrue(
                    world.spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
                    "Native capacity actor registration failed");
                helper.assertTrue(
                    maid.selectBrainProfileForDevelopment(profile) == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Complete F/N root selection failed");
                if (profile == MaidBrainProfile.FAITHFUL_TLM) helper.assertTrue(
                    maid.getBrainCoordinator()
                        .peekFaithfulBrain() != null,
                    "Narrow component substituted for complete F");
                else helper.assertTrue(
                    maid.getBrainCoordinator()
                        .peekEnhancedCore() != null,
                    "Narrow component substituted for complete N");
                var input = new JsonObject();
                input.addProperty("stage", instrumented ? "N-30-fairness" : stageLabel());
                input.addProperty("profile", profile.getId());
                input.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                input.addProperty(
                    "owner",
                    owner.getUniqueID()
                        .toString());
                input.addProperty(
                    "actualEntityClass",
                    maid.getClass()
                        .getName());
                input.addProperty(
                    "flightContract",
                    profile == MaidBrainProfile.FAITHFUL_TLM ? maid.getBrainCoordinator()
                        .peekFaithfulBrain()
                        .getFlightProfile()
                        .name()
                        : maid.getBrainCoordinator()
                            .peekEnhancedCore()
                            .getFlightProfile()
                            .name());
                input.add("nativeInput", SleepAttributePortSnapshot.capture(maid));
                inputs.add(input);
                if (instrumented) maid.getBrainCoordinator()
                    .getTelemetry()
                    .start("load-fairness-" + i, 4096, 256);
                else helper.assertFalse(
                    maid.getBrainCoordinator()
                        .getTelemetry()
                        .isEnabled(),
                    "Capacity AI event ledger must remain disabled");
            }
        }

        void tick() {
            if (complete) return;
            helper.assertTrue(
                MaidSpawnChecks.present(owner) && owner.worldObj == world && server.getCanSpawnAnimals(),
                "Native capacity owner/world/animal eligibility changed");
            for (var maid : actors) helper.assertTrue(
                MaidSpawnChecks.present(maid) && maid.isOwner(owner) && maid.isEntityAlive(),
                "Natural capacity actor died, unloaded or lost ownership");
            drainChannel();
            if (draining) {
                if (++drainAge >= 4) {
                    helper.assertTrue(
                        MaidWorldNavigationBudget.forWorld(world)
                            .queued() == 0
                            && MaidWorldSensingBudget.forWorld(world)
                                .queued() == 0,
                        "Owned request queues did not drain after exact actor removal");
                    probe.close();
                    complete = true;
                    save("passed");
                }
                return;
            }
            if (fairness) {
                observeFairness();
                moveOwner(++fairnessAge);
                if (fairnessAge >= 240) {
                    finishFairness();
                    removeActors();
                    draining = true;
                    probe.phase("queue-drain", 0, false);
                }
                return;
            }
            moveOwner(++age);
            if (age == warmup) probe.phase(stageLabel(), actors.size(), true);
            if (age < warmup + sampled) return;
            var row = roster();
            row.addProperty("label", stageLabel());
            row.add("nativeStorage", storage());
            stages.add(row);
            removeActors();
            stage++;
            if (stage < counts.length) beginStage();
            else {
                fairness = true;
                moveOwner(0);
                spawnActors(30, MaidBrainProfile.ENHANCED, true);
                probe.phase("N-30-fairness-instrumented", 30, false);
            }
            save("running");
        }

        void moveOwner(int tick) {
            int cycle = tick % 240;
            double offset = cycle <= 120 ? cycle * .18 : (240 - cycle) * .18;
            owner.setPosition(x + 30 + offset, y + 1, z + 24);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            owner.fallDistance = 0;
        }

        void observeFairness() {
            long now = world.getTotalWorldTime();
            var navigation = MaidWorldNavigationBudget.forWorld(world);
            var sensing = MaidWorldSensingBudget.forWorld(world);
            int navUsed = navigation.used(now), scanUsed = sensing.used(now);
            helper.assertTrue(
                navUsed <= MaidNavigationConfig.queriesPerTick && scanUsed <= MaidPerceptionConfig.scansPerTick,
                "Actual native request admission exceeded configured per-world budget");
            var row = new JsonObject();
            row.addProperty("worldTick", now);
            row.addProperty("navigationUsed", navUsed);
            row.addProperty("navigationQueued", navigation.queued());
            row.addProperty("sensingUsed", scanUsed);
            row.addProperty("sensingQueued", sensing.queued());
            var samples = new JsonArray();
            for (var maid : actors) {
                var core = maid.getBrainCoordinator()
                    .peekEnhancedCore();
                helper.assertTrue(core != null, "Complete N root disappeared during fairness observation");
                var value = new JsonObject();
                value.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                value.addProperty(
                    "navigationOutcome",
                    core.navigation()
                        .outcome()
                        .name());
                value.addProperty(
                    "navigationWaitTicks",
                    core.navigation()
                        .waitingCount());
                value.addProperty(
                    "livingScans",
                    core.perception()
                        .livingScanCount());
                value.addProperty(
                    "pickupScans",
                    core.perception()
                        .pickupScanCount());
                for (var kind : MaidSensingBudget.Kind.values()) value.addProperty(
                    "queued" + kind.name(),
                    core.sensing()
                        .queued(kind));
                samples.add(value);
            }
            row.add("actors", samples);
            fairnessFrames.add(row);
        }

        void finishFairness() {
            var result = new JsonArray();
            int requesters = 0;
            for (var maid : actors) {
                var channel = maid.getBrainCoordinator()
                    .getTelemetry();
                helper.assertTrue(channel.getFault() == null, "Fairness event recorder faulted");
                var snapshot = channel.snapshot();
                long submitted = snapshot.counters.get(MaidTelemetryLedger.Counter.NAV_SUBMITTED);
                long returned = snapshot.counters.get(MaidTelemetryLedger.Counter.NAV_RETURNED);
                long waited = maid.getBrainCoordinator()
                    .peekEnhancedCore()
                    .navigation()
                    .waitingCount();
                helper.assertTrue(
                    submitted == returned && snapshot.rejectedEvents == 0 && snapshot.droppedEvents == 0,
                    "Actual native request evidence was incomplete");
                if (submitted > 0 || waited > 0) requesters++;
                helper.assertTrue(
                    waited == 0 || returned > 0,
                    "A naturally queued actor received no native service in the entire bounded fairness window");
                var row = new JsonObject();
                row.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                row.addProperty("actualNativeQueries", submitted);
                row.addProperty("actualNativeReturns", returned);
                row.addProperty("actualBudgetWaitTicks", waited);
                row.addProperty(
                    "coverage",
                    submitted > 0 ? "ACTUAL_REQUEST_RETURNED" : "NO_NATIVE_REQUEST_NOT_COVERED");
                row.add("telemetry", new Gson().toJsonTree(snapshot));
                result.add(row);
                channel.close();
            }
            report.addProperty("fairnessActualRequesters", requesters);
            report.addProperty(
                "fairnessScope",
                "Natural N roots, 240 actual ticks; only actual requesters covered, no grant inferred for idle actors; RETURNED is not path success");
            report.add("fairnessActors", result);
            report.add("fairnessFrames", fairnessFrames);
            report.add("actorInputs", inputs);
            helper.assertTrue(requesters > 0, "Natural fairness window exercised no actual native path requests");
        }

        JsonObject roster() {
            var row = new JsonObject();
            var values = new JsonArray();
            for (var maid : actors) {
                var value = new JsonObject();
                value.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                value.addProperty("age", maid.ticksExisted);
                value.addProperty("x", maid.posX);
                value.addProperty("y", maid.posY);
                value.addProperty("z", maid.posZ);
                value.addProperty("actualRegistered", MaidSpawnChecks.present(maid));
                value.addProperty(
                    "telemetryEnabled",
                    maid.getBrainCoordinator()
                        .getTelemetry()
                        .isEnabled());
                values.add(value);
                helper.assertTrue(
                    maid.ticksExisted >= warmup + sampled - 2,
                    "Actor capacity sample did not span native entity ticks");
            }
            row.add("actors", values);
            row.addProperty("worldLoadedEntities", world.loadedEntityList.size());
            row.addProperty("worldTime", world.getTotalWorldTime());
            return row;
        }

        JsonObject storage() {
            var out = new JsonObject();
            var root = world.getSaveHandler()
                .getWorldDirectory()
                .toPath();
            long bytes = 0, files = 0;
            try (var paths = Files.walk(root)) {
                for (var path : (Iterable<java.nio.file.Path>) paths::iterator) if (Files.isRegularFile(path)) {
                    String name = path.getFileName()
                        .toString();
                    if (name.endsWith(".mca") || name.endsWith(".dat")) {
                        bytes += Files.size(path);
                        files++;
                    }
                }
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot observe native saved-file growth", failure);
            }
            out.addProperty("nativeDataFiles", files);
            out.addProperty("nativeDataBytes", bytes);
            out.addProperty(
                "scope",
                "Observed native dat/MCA allocation after ordinary periodic saves; not forced durability and not a leak verdict");
            return out;
        }

        void removeActors() {
            for (var maid : actors) {
                helper.assertTrue(
                    maid.worldObj == world && MaidSpawnChecks.present(maid),
                    "Unknown roster successor retained");
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.getBrainCoordinator()
                    .getTelemetry()
                    .close();
                world.removePlayerEntityDangerously(maid);
                maid.setDead();
                helper.assertTrue(
                    world.loadedEntityList.stream()
                        .noneMatch(value -> value == maid) && world.getEntityByID(maid.getEntityId()) != maid,
                    "Exact owned load actor remained registered");
            }
            actors.clear();
        }

        void drainChannel() {
            if (channel == null) return;
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        }

        void save(String state) {
            report.addProperty("state", state);
            report.add("stages", stages);
            report.add("fairnessFrames", fairnessFrames);
            report.add("actorInputs", inputs);
            if (probe != null) report.add("metrics", probe.snapshot());
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "server-load-profile.json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain native server load evidence", failure);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (probe != null) probe.close();
                removeActors();
                if (owner != null) {
                    if (server.getConfigurationManager().playerEntityList.contains(owner))
                        server.getConfigurationManager()
                            .playerLoggedOut(owner);
                    if (owner.worldObj == world) world.removePlayerEntityDangerously(owner);
                    owner.setDead();
                }
                if (channel != null) {
                    channel.finish();
                    drainChannel();
                }
                for (var block : blocks) {
                    helper.assertTrue(
                        world.getBlock(block.x, block.y, block.z) == Blocks.stone
                            && world.getBlockMetadata(block.x, block.y, block.z) == 0,
                        "Unknown floor successor retained");
                    world.setBlock(block.x, block.y, block.z, Blocks.air, 0, 2);
                }
                helper.assertTrue(
                    allActors.stream()
                        .noneMatch(value -> world.loadedEntityList.contains(value)),
                    "Owned load actor leaked");
                report.addProperty("exactOwnedCleanup", true);
            } finally {
                chunks.releaseAll();
                server.setCanSpawnAnimals(oldAnimals);
                MaidAcquisitionConfig.giveInitialSlab = oldGift;
                world.getGameRules()
                    .setOrCreateGameRule("doMobSpawning", oldSpawning);
                world.getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", oldDaylight);
                world.setWorldTime(oldTime + world.getTotalWorldTime() - started);
                save(complete ? "passed-and-restored" : "failed-and-restored");
            }
        }
    }

    private static final class BlockBefore {

        final int x, y, z;

        BlockBefore(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
