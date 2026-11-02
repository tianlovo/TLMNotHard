package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;

import net.minecraft.block.BlockBed;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 离线家园自然睡眠观察，使用生产工厂选择完整F/N，不设置睡姿或手动驱动AI */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NaturalSleepPortTests {

    private NaturalSleepPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 580, batch = "sleep_natural_faithful")
    public static void collectFreshOfflineHomeFaithfulActors(GameTestHelper helper) {
        collect(helper, MaidBrainProfile.FAITHFUL_TLM);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 580, batch = "sleep_natural_enhanced")
    public static void collectFreshOfflineHomeEnhancedActors(GameTestHelper helper) {
        collect(helper, MaidBrainProfile.ENHANCED);
    }

    private static void collect(GameTestHelper helper, MaidBrainProfile profile) {
        collectMechanism(helper, profile, null);
    }

    static void collectMechanism(GameTestHelper helper, MaidBrainProfile profile, String mechanism) {
        var collector = mechanism == null ? new Collector(helper, profile)
            : new SleepMechanismPortTests.Collector(helper, profile, mechanism);
        helper.afterTest(collector::close);
        collector.setup();
        FMLCommonHandler.instance()
            .bus()
            .register(collector);
        MinecraftForge.EVENT_BUS.register(collector);
        helper.onEachTick(
            "natural sleep observer failure",
            () -> { if (collector.failure != null) throw collector.failure; });
        helper.succeedWhen(() -> collector.complete);
    }

    public static class Collector {

        final GameTestHelper helper;
        final MaidBrainProfile profile;
        final String resource, expectedContract, outputName;
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final JsonObject output = new JsonObject();
        final JsonArray runs = new JsonArray();
        final UUID offlineOwner = UUID.randomUUID();
        final int x, y, z;
        final long oldTime;
        final String oldDaylight, oldSpawning;
        final boolean oldAnimals;
        final int oldSleepRange = MaidHomeConfig.sleepRange;
        JsonObject contract;
        EntityMaid maid;
        MaidPolicyRuntime boundRuntime;
        TileEntityMaidBed bed;
        JsonArray frames;
        int repetition, warmup, previousAge, livingTicks;
        boolean complete, closed;
        RuntimeException failure;

        Collector(GameTestHelper helper, MaidBrainProfile profile) {
            this(
                helper,
                profile,
                "/tlmnh-sleep/contract.json",
                "SLEEP_NATURAL_HOME_OFFLINE_O_V1",
                "sleep-natural-" + profile.getId() + ".json");
        }

        Collector(GameTestHelper helper, MaidBrainProfile profile, String resource, String expectedContract,
            String outputName) {
            this.resource = resource;
            this.expectedContract = expectedContract;
            this.outputName = outputName;
            this.helper = helper;
            this.profile = profile;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            oldTime = helper.getWorld()
                .getWorldTime();
            oldDaylight = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            oldSpawning = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            oldAnimals = MinecraftServer.getServer()
                .getCanSpawnAnimals();
            output.addProperty("schema", "sleep-natural-port-feasibility-v1");
            output.addProperty("profile", profile.getId());
            output.addProperty("benchmarkVerdict", "NOT_EVALUATED");
            output.addProperty("completeB10Accepted", false);
            output.addProperty("randomSourcesReset", false);
            output.addProperty("samplePhase", "WorldTickEvent.END");
            output.add("runs", runs);
        }

        void setup() {
            try (var stream = NaturalSleepPortTests.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing natural sleep input contract");
                byte[] bytes = com.google.common.io.ByteStreams.toByteArray(stream);
                contract = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                var hash = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256")
                    .digest(bytes)) hash.append(String.format("%02x", value & 255));
                output.addProperty("contractSha256", hash.toString());
                output.add("contract", contract);
                output.add(
                    "productionArtifact",
                    DevelopmentArtifactIdentity.capture(
                        EntityMaid.class,
                        MaidFaithfulBrain.class,
                        EnhancedCoreBrain.class,
                        MaidPolicyRuntime.class,
                        com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor.class,
                        com.tianluoqaq.tlmnh.common.life.MaidHomeArea.class,
                        TileEntityMaidBed.class));
                output.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity.capture(NaturalSleepPortTests.class, Collector.class));
                chunks.forceChunksStrict(helper.getWorld(), x - 16, y, z - 16, x + 33, y + 10, z + 25);
            } catch (Exception error) {
                throw new IllegalStateException("Sleep observer setup failed", error);
            }
            helper.assertTrue(
                contract.get("contract")
                    .getAsString()
                    .equals(expectedContract)
                    && contract.get("dayTime")
                        .getAsLong() == 6000
                    && contract.get("bedFacing")
                        .getAsString()
                        .equals("NORTH"),
                "Unsupported natural sleep inputs");
            MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
            MaidHomeConfig.sleepRange = 6;
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            helper.getWorld()
                .setWorldTime(6000);
            for (int a = 0; a < 21; a++) for (int b = 0; b < 11; b++)
                for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, b == 0 && a < 17 ? Blocks.stone : Blocks.air);
            int direction = 2;
            helper.assertTrue(
                BlockBed.field_149981_a[direction][0] == 0 && BlockBed.field_149981_a[direction][1] == -1,
                "Native bed metadata does not represent north");
            helper.setBlock(7, 1, 5, MaidLifeContent.maidBed, direction);
            helper.setBlock(7, 1, 4, MaidLifeContent.maidBed, direction | 8);
            bed = (TileEntityMaidBed) helper.getWorld()
                .getTileEntity(x + 7, y + 1, z + 4);
            helper.assertTrue(bed != null && bed.complete(), "Natural sleep bed is incomplete");
        }

        @SubscribeEvent
        public void living(LivingEvent.LivingUpdateEvent event) {
            if (event.entityLiving == maid) livingTicks++;
        }

        @SubscribeEvent
        public void tick(TickEvent.WorldTickEvent event) {
            if (closed || complete
                || failure != null
                || event.phase != TickEvent.Phase.END
                || event.world != helper.getWorld()) return;
            try {
                observe();
            } catch (Exception | LinkageError | AssertionError error) {
                failure = new IllegalStateException("Natural sleep capture failed", error);
                output.addProperty("error", error.toString());
            }
        }

        private void spawn() {
            helper.assertTrue(
                maid == null && offlineOwnerAbsent(),
                "Offline owner unexpectedly online or old actor retained");
            maid = new EntityMaid(helper.getWorld());
            helper.assertTrue(maid.bindTo(offlineOwner), "Offline owner UUID binding failed");
            maid.setSchedule(MaidSchedule.NIGHT);
            maid.setPickup(false);
            maid.setSitting(false);
            maid.setPosition(x + 7.5, y + 1, z + 4.5);
            var home = maid.getHomeArea();
            helper.assertTrue(
                home.setEnabled(true) && home.capture(Activity.WORK)
                    && home.capture(Activity.IDLE)
                    && home.capture(Activity.REST),
                "Natural home anchors rejected");
            helper.spawnEntity(maid, 2.5, 1, 4.5);
            var selected = maid.selectBrainProfileForDevelopment(profile);
            helper.assertTrue(
                selected == MaidPolicyRuntime.SelectionResult.SELECTED,
                "Natural production profile selection failed");
            boundRuntime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            livingTicks = previousAge = 0;
            var row = new JsonObject();
            frames = new JsonArray();
            row.addProperty("repetition", repetition);
            row.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            row.addProperty(
                "entityClass",
                maid.getClass()
                    .getName());
            row.addProperty("selection", selected.name());
            row.addProperty(
                "actualProfile",
                boundRuntime.getProfile()
                    .getId());
            row.add("initialPosition", vector(maid.posX - x, maid.posY - y, maid.posZ - z));
            row.add("homeCenter", vector(home.center().x - x, home.center().y - y, home.center().z - z));
            row.addProperty("actualRestrictRadius", home.restrictionRadius());
            row.addProperty(
                "bedDirectionMetadata",
                helper.getWorld()
                    .getBlockMetadata(x + 7, y + 1, z + 4));
            row.add("frames", frames);
            runs.add(row);
            afterSpawn();
        }

        protected void afterSpawn() {}

        protected void afterFrame(JsonObject sample) {}

        private void observe() {
            if (maid == null) {
                if (++warmup >= contract.get("warmupTicks")
                    .getAsInt()) spawn();
                return;
            }
            helper.assertTrue(
                maid.getClass() == EntityMaid.class && maid.isEntityAlive()
                    && helper.getWorld()
                        .getEntityByID(maid.getEntityId()) == maid
                    && maid.getOwner() == null
                    && maid.func_152113_b()
                        .equals(offlineOwner.toString())
                    && maid.ticksExisted == previousAge + 1
                    && livingTicks == 1,
                "Natural actor registration, owner or one-tick phase changed");
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            var home = maid.getHomeArea();
            helper.assertTrue(
                runtime == boundRuntime && runtime.isReady()
                    && runtime.getProfile() == profile
                    && home.enabled()
                    && home.activity() == Activity.REST
                    && home.restrictionRadius() == 6
                    && maid.getSchedule() == MaidSchedule.NIGHT
                    && helper.getWorld()
                        .getWorldTime() == 6000,
                "Natural full profile, home or day-time changed");
            var sample = new JsonObject();
            sample.addProperty("frame", frames.size());
            sample.addProperty("age", maid.ticksExisted);
            sample.addProperty(
                "gameTime",
                helper.getWorld()
                    .getTotalWorldTime());
            sample.addProperty(
                "dayTime",
                helper.getWorld()
                    .getWorldTime());
            sample.addProperty("livingTicks", livingTicks);
            sample.addProperty(
                "actualProfile",
                runtime.getProfile()
                    .getId());
            sample.add("position", vector(maid.posX - x, maid.posY - y, maid.posZ - z));
            sample.add("motion", vector(maid.motionX, maid.motionY, maid.motionZ));
            sample.addProperty("sleeping", maid.hasPose(MaidPose.SLEEPING));
            sample.addProperty("sleepDirection", maid.getSleepingBedDirection());
            helper.assertTrue(
                maid.getSleepingBedDirection() == (maid.hasPose(MaidPose.SLEEPING) ? helper.getWorld()
                    .getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 3 : -1),
                "Natural sleep direction missing or stale while awake");
            sample.addProperty("health", maid.getHealth());
            sample.addProperty(
                "activity",
                home.activity()
                    .name());
            sample.addProperty(
                "bedOccupied",
                (helper.getWorld()
                    .getBlockMetadata(x + 7, y + 1, z + 4) & 4) != 0);
            sample.addProperty("bedOwned", bed.isOwnedBy(maid));
            sample.addProperty(
                "navigationDone",
                maid.getNavigator()
                    .noPath());
            frames.add(sample);
            afterFrame(sample);
            previousAge = maid.ticksExisted;
            livingTicks = 0;
            if (frames.size() < contract.get("framesPerActor")
                .getAsInt()) return;
            removeMaid();
            repetition++;
            if (repetition < contract.get("repetitions")
                .getAsInt()) spawn();
            else complete = true;
        }

        private void removeMaid() {
            if (maid == null) return;
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(maid);
            helper.assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Sleep observer actor retained");
            helper.assertFalse(bed != null && bed.isOwnedBy(maid), "Sleep observer left its bed lease");
            maid = null;
        }

        private boolean offlineOwnerAbsent() {
            for (Object entry : helper.getWorld().playerEntities)
                if (entry instanceof net.minecraft.entity.player.EntityPlayer player && player.getUniqueID()
                    .equals(offlineOwner)) return false;
            for (Object entry : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList)
                if (entry instanceof net.minecraft.entity.player.EntityPlayer player && player.getUniqueID()
                    .equals(offlineOwner)) return false;
            return true;
        }

        void close() {
            if (closed) return;
            closed = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            MinecraftForge.EVENT_BUS.unregister(this);
            try {
                removeMaid();
                for (int a = 0; a < 21; a++)
                    for (int b = 0; b < 11; b++) for (int c = 0; c < 9; c++) helper.setBlock(a, b, c, Blocks.air);
                output.addProperty("ownCleanupComplete", true);
            } finally {
                MaidHomeConfig.sleepRange = oldSleepRange;
                MinecraftServer.getServer()
                    .setCanSpawnAnimals(oldAnimals);
                helper.getWorld()
                    .setWorldTime(oldTime);
                helper.getWorld()
                    .getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", oldDaylight);
                helper.getWorld()
                    .getGameRules()
                    .setOrCreateGameRule("doMobSpawning", oldSpawning);
                chunks.releaseAll();
                output.addProperty("observationComplete", complete && failure == null);
                try {
                    Files.write(
                        Paths.get(System.getProperty("horizonqa.reportDir"), outputName),
                        new Gson().toJson(output)
                            .getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("Cannot retain sleep observation", error);
                }
            }
        }

        private static JsonArray vector(double x, double y, double z) {
            var values = new JsonArray();
            values.add(new JsonPrimitive(x));
            values.add(new JsonPrimitive(y));
            values.add(new JsonPrimitive(z));
            return values;
        }
    }
}
