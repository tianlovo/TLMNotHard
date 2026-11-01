package com.tianluoqaq.tlmnh.qa;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import net.minecraft.block.Block;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 原自然跟随合同的移植侧观察器；原版身份与完整F准入另外判断，不把收集完成当能力PASS */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NaturalFollowPortTests {

    private NaturalFollowPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 2300, batch = "natural_follow_port")
    public static void collectSameOwnerInputsOnFreshNaturalActors(GameTestHelper helper) {
        collect(helper, false);
    }

    public static void collect(GameTestHelper helper, boolean water) {
        var collector = new Collector(helper, water);
        FMLCommonHandler.instance()
            .bus()
            .register(collector);
        helper.onEachTick(
            "natural observer failure delivery",
            () -> { if (collector.failure != null) throw collector.failure; });
        helper.succeedWhen(() -> collector.complete);
    }

    public static final class Collector {

        final JsonObject contract;
        final JsonArray scenes;
        final boolean water;
        final List<SavedBlock> terrain = new ArrayList<>();
        String terrainKind;

        final GameTestHelper helper;
        final ModernCoreTests.Fixture fixture;
        final JsonObject output = new JsonObject();
        final JsonArray runs = new JsonArray();
        final String daylight;
        final List<SavedBlock> outside = new ArrayList<>();
        EntityMaid maid;
        MaidFaithfulBrain core;
        EnhancedCoreBrain enhanced;
        final MaidBrainProfile expectedProfile = requestedProfile();
        com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime boundRuntime;
        String bindingEntry;
        String selectionResult;
        JsonArray frames;
        JsonObject row;
        int caseIndex, repetition, warmup, frame, total;
        boolean complete, closed;
        boolean prepared;
        RuntimeException failure;
        double plannedX, plannedY, plannedZ;

        Collector(GameTestHelper helper, boolean water) {
            this.helper = helper;
            this.water = water;
            String resource = "/tlmnh-follow-contracts/" + (water ? "contract.json" : "flat-contract-v1.json");
            try (var input = NaturalFollowPortTests.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing natural input contract");
                byte[] bytes = com.google.common.io.ByteStreams.toByteArray(input);
                contract = new com.google.gson.JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                var hash = new StringBuilder();
                for (byte value : java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes)) hash.append(String.format("%02x", value & 255));
                output.addProperty("contractSha256", hash.toString());
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("Cannot read natural input contract", failure);
            }
            helper.assertTrue(
                contract.get("contract")
                    .getAsString()
                    .equals(water ? "FOLLOW_NATURAL_WATER_O_V1" : "FOLLOW_NATURAL_O_V1"),
                "Unexpected natural input contract");
            scenes = contract.getAsJsonArray("cases");
            // HQA按登记顺序清理，先归还子场景，再让父夹具清模板和释放主人/区块
            helper.afterTest(() -> { if (prepared) close(); });
            fixture = new ModernCoreTests.Fixture(helper, false, 1000, 8);
            fixture.maid.getBrainCoordinator()
                .setSuspended(true);
            fixture.maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(fixture.maid);
            daylight = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            fixture.owner.capabilities.isCreativeMode = true;
            helper.assertTrue(fixture.owner.getHeldItem() == null, "Natural owner must have an empty main hand");
            for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) for (int y = 0; y <= 10; y++) {
                if (x >= 0 && x < 21 && z >= 0 && z < 9) continue;
                var p = helper.absolute(x, y, z);
                helper.assertTrue(
                    helper.getWorld()
                        .getTileEntity(p.x(), p.y(), p.z()) == null,
                    "Unexpected outside TileEntity");
                outside.add(
                    new SavedBlock(
                        p.x(),
                        p.y(),
                        p.z(),
                        helper.getWorld()
                            .getBlock(p.x(), p.y(), p.z()),
                        helper.getWorld()
                            .getBlockMetadata(p.x(), p.y(), p.z())));
            }
            prepared = true;
            for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) {
                helper.setBlock(x, 0, z, Blocks.stone);
                for (int y = 1; y <= 10; y++) helper.setBlock(x, y, z, Blocks.air);
            }
            output.addProperty("schema", "follow-natural-port-observation-v2");
            output.addProperty("expectedProfile", expectedProfile.getId());
            try {
                output.add(
                    "productionArtifact",
                    com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity.capture(
                        EntityMaid.class,
                        expectedProfile == MaidBrainProfile.ENHANCED_CORE_COMPONENT ? EnhancedCoreBrain.class
                            : MaidFaithfulBrain.class,
                        com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.class,
                        ModernFollowCoreActor.class));
                output.add(
                    "observerArtifact",
                    com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity.capture(
                        NaturalFollowPortTests.class,
                        Collector.class,
                        NaturalWaterPortTests.class,
                        ModernCoreTests.Fixture.class));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot bind actual natural observation artifacts", failure);
            }
            output.addProperty(
                "contract",
                contract.get("contract")
                    .getAsString());
            output.addProperty("candidateIsCompleteFaithfulProfile", false);
            output.addProperty("candidateIsCompleteEnhancedProfile", false);
            output.addProperty("originalEquivalencePassed", false);
            output.addProperty("statisticalAdmission", false);
            output.addProperty("worldRngAndSensorPhaseUncontrolled", true);
            output.addProperty(
                "ownerKind",
                "standard Forge FakePlayer in online/world tables, synthetic position inputs");
            output.addProperty(
                "sourceFlightProfile",
                expectedProfile == MaidBrainProfile.ENHANCED_CORE_COMPONENT
                    ? "NATIVE_MOTION_COMPENSATION (no bow in these scenes)"
                    : "SOURCE_ORIGINAL (no bow in these scenes)");
            output.add("runs", runs);
            var potions = new JsonArray();
            for (var potion : Potion.potionTypes) if (potion != null) {
                var p = new JsonObject();
                p.addProperty("id", potion.id);
                p.addProperty("key", potion.getName());
                p.addProperty(
                    "class",
                    potion.getClass()
                        .getName());
                potions.add(p);
            }
            output.add("actualPotionRegistry", potions);
            prepareTerrain();
            moveOwner(0);
        }

        private JsonObject scene() {
            return scenes.get(caseIndex)
                .getAsJsonObject();
        }

        private void prepareTerrain() {
            if (!water) return;
            clearTerrain();
            terrainKind = scene().get("terrain")
                .getAsString();
            helper.assertTrue(
                terrainKind.equals("submerged_channel") || terrainKind.equals("submerged_low_tunnel"),
                "Unsupported water terrain");
            for (int x = -2; x <= 16; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++) {
                boolean wall = x == -2 || x == 16 || z == -2 || z == 2;
                boolean roof = terrainKind.equals("submerged_low_tunnel") && x >= 4 && x <= 7 && y >= 1;
                if (!wall && !roof && y == 3) continue;
                var at = helper.absolute(x, y + 1, z);
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(at.x(), at.y(), at.z()) == Blocks.air,
                    "Water terrain requires prepared air");
                var block = wall || roof ? Blocks.glass : Blocks.water;
                terrain.add(new SavedBlock(at.x(), at.y(), at.z(), block, 0));
                helper.assertTrue(
                    helper.getWorld()
                        .setBlock(at.x(), at.y(), at.z(), block, 0, 2),
                    "Water terrain placement rejected");
            }
        }

        private JsonObject verifyTerrain() {
            var result = new JsonObject();
            int waterCells = 0, glassCells = 0;
            for (var cell : terrain) {
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(cell.x, cell.y, cell.z) == cell.block
                        && helper.getWorld()
                            .getBlockMetadata(cell.x, cell.y, cell.z) == cell.metadata
                        && helper.getWorld()
                            .getTileEntity(cell.x, cell.y, cell.z) == null,
                    "Water terrain changed");
                if (cell.block == Blocks.water) waterCells++;
                else glassCells++;
            }
            result.addProperty("kind", terrainKind);
            result.addProperty("waterCells", waterCells);
            result.addProperty("glassCells", glassCells);
            result.addProperty("cells", terrain.size());
            return result;
        }

        private void clearTerrain() {
            if (terrain.isEmpty()) return;
            verifyTerrain();
            for (var block : new Block[] { Blocks.water, Blocks.glass })
                for (var cell : terrain) if (cell.block == block) {
                    helper.assertTrue(
                        helper.getWorld()
                            .setBlock(cell.x, cell.y, cell.z, Blocks.air, 0, 2),
                        "Water terrain removal rejected");
                    helper.assertTrue(
                        helper.getWorld()
                            .getBlock(cell.x, cell.y, cell.z) == Blocks.air,
                        "Water terrain did not return to air");
                }
            terrain.clear();
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (failure != null) return;
            try {
                observe(event);
            } catch (RuntimeException | AssertionError | LinkageError exception) {
                com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.throwIfFatal(exception);
                failure = new IllegalStateException("Natural observation failed", exception);
            }
        }

        private void observe(TickEvent.ServerTickEvent event) {
            if (closed || complete) return;
            if (event.phase == TickEvent.Phase.START) {
                if (maid != null) moveOwner(frame);
                return;
            }
            if (maid == null) {
                if (++warmup >= 20) spawn();
                return;
            }
            helper.assertTrue(
                maid.getClass() == EntityMaid.class && maid.isEntityAlive()
                    && maid.getHealth() == 20
                    && helper.getWorld()
                        .getEntityByID(maid.getEntityId()) == maid
                    && maid.getOwner() == fixture.owner
                    && maid.ticksExisted == frame + 1
                    && maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .isReady(),
                "Natural actor registration, ownership, health, one-tick phase or policy changed");
            helper.assertTrue(
                Math.abs(fixture.owner.posX - plannedX) < .001 && Math.abs(fixture.owner.posZ - plannedZ) < .001
                    && Math.abs(fixture.owner.posY - plannedY) < .001,
                "Natural owner input did not land");
            var sample = new JsonObject();
            sample.add("policy", policySnapshot());
            sample.addProperty("index", frame);
            sample.addProperty("age", maid.ticksExisted);
            sample.add("position", vector(maid.posX - fixture.x, maid.posY - fixture.y - 1, maid.posZ - fixture.z));
            sample.add("motion", vector(maid.motionX, maid.motionY, maid.motionZ));
            sample.add(
                "owner",
                vector(
                    fixture.owner.posX - fixture.x,
                    fixture.owner.posY - fixture.y - 1,
                    fixture.owner.posZ - fixture.z));
            sample.addProperty("width", maid.width);
            sample.addProperty("height", maid.height);
            sample.addProperty("stepHeight", maid.stepHeight);
            sample.addProperty("onGround", maid.onGround);
            sample.addProperty("health", maid.getHealth());
            sample.addProperty(
                "activity",
                enhanced == null ? core.getActivities()
                    .active()
                    .name()
                    : maid.getHomeArea()
                        .activity()
                        .name());
            if (enhanced != null) sample.addProperty(
                "enhancedGoal",
                enhanced.selectedKind() == null ? null
                    : enhanced.selectedKind()
                        .name());
            sample.addProperty(
                "speed",
                maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue());
            sample.addProperty("teleportAttempts", actor().teleportAttempts());
            var path = maid.getNavigator()
                .getPath();
            sample.addProperty("pathPresent", path != null);
            if (path != null) {
                sample.addProperty("pathIndex", path.getCurrentPathIndex());
                sample.addProperty("pathLength", path.getCurrentPathLength());
                if (path instanceof com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath modern) {
                    var nodes = new JsonArray();
                    var types = new JsonArray();
                    var costs = new JsonArray();
                    for (var node : modern.result.nodes) {
                        nodes.add(vector(node.x - fixture.x, node.y - fixture.y - 1, node.z - fixture.z));
                        types.add(new com.google.gson.JsonPrimitive(node.type.name()));
                        costs.add(new com.google.gson.JsonPrimitive(node.cost));
                    }
                    sample.add("nodes", nodes);
                    sample.add("nodeTypes", types);
                    sample.add("nodeCosts", costs);
                }
            }
            frames.add(sample);
            total++;
            if (++frame == scene().get("ticks")
                .getAsInt()) {
                if (water) row.add("terrainAfter", verifyTerrain());
                row.addProperty("complete", true);
                removeMaid();
                row.addProperty("actorRemoved", true);
                System.out.println(
                    "TLMNH_NATURAL_PORT_RUN case=" + scene().get("id")
                        .getAsString() + " repetition=" + repetition);
                if (++repetition == contract.get("rebuilds")
                    .getAsInt()) {
                    repetition = 0;
                    caseIndex++;
                }
                if (caseIndex == scenes.size()) complete = true;
                else {
                    warmup = 0;
                    prepareTerrain();
                    moveOwner(0);
                }
            }
        }

        private void spawn() {
            maid = new EntityMaid(helper.getWorld());
            helper.assertTrue(maid.bindTo(fixture.owner.getUniqueID()), "Natural owner binding rejected");
            maid.setSchedule(MaidSchedule.ALL);
            var spawn = contract.getAsJsonArray("maidSpawn");
            helper.spawnEntity(
                maid,
                spawn.get(0)
                    .getAsDouble(),
                spawn.get(1)
                    .getAsDouble() + 1,
                spawn.get(2)
                    .getAsDouble());
            if (expectedProfile == MaidBrainProfile.FAITHFUL_TLM
                || expectedProfile == MaidBrainProfile.ENHANCED_CORE_COMPONENT) {
                var selected = maid.selectBrainProfileForDevelopment(expectedProfile);
                helper.assertTrue(
                    selected == com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Natural actor requested factory selection failed");
                if (expectedProfile == MaidBrainProfile.ENHANCED_CORE_COMPONENT) enhanced = maid.getBrainCoordinator()
                    .peekEnhancedCore();
                else core = maid.getBrainCoordinator()
                    .peekFaithfulBrain();
                bindingEntry = "entity_protected_profile_selection";
                selectionResult = selected.name();
            } else {
                core = new MaidFaithfulBrain(maid);
                maid.getBrainCoordinator()
                    .setAfterNavigationActionsForDevelopment(core);
                bindingEntry = "explicit_development_component";
                selectionResult = "NOT_APPLICABLE";
            }
            boundRuntime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            helper.assertTrue(
                (core != null || enhanced != null) && boundRuntime != null,
                "Natural actor has no bound Brain or runtime");
            if (enhanced == null) actor().rangedBow()
                .setFlightProfileForDevelopment(ModernFlightProfile.SOURCE_ORIGINAL);
            frame = 0;
            frames = new JsonArray();
            row = new JsonObject();
            row.addProperty(
                "id",
                scene().get("id")
                    .getAsString());
            row.add("input", new com.google.gson.JsonParser().parse(scene().toString()));
            if (water) row.add("terrainBefore", verifyTerrain());
            row.addProperty("rebuild", repetition);
            row.addProperty(
                "maidUuid",
                maid.getUniqueID()
                    .toString());
            row.addProperty(
                "ownerUuid",
                fixture.owner.getUniqueID()
                    .toString());
            row.addProperty("bindingEntry", bindingEntry);
            row.addProperty("selectionResult", selectionResult);
            row.add("initialPolicy", policySnapshot());
            row.addProperty("pickup", maid.isPickup());
            row.addProperty("complete", false);
            row.addProperty("actorRemoved", false);
            row.add("frames", frames);
            runs.add(row);
        }

        private JsonObject policySnapshot() {
            var brain = maid.getBrainCoordinator();
            var runtime = brain.peekPolicyRuntime();
            boolean stable = runtime == boundRuntime && (enhanced == null ? brain.peekFaithfulBrain() == core
                : brain.peekEnhancedCore() == enhanced && brain.peekFaithfulBrain() == null);
            helper.assertTrue(
                stable && runtime != null
                    && runtime.isReady()
                    && runtime.getProfile() == expectedProfile
                    && runtime.requiresAfterNativeNavigation()
                    && runtime.getContext()
                        .getMaidId()
                        .equals(maid.getUniqueID()),
                "Natural capture policy, runtime, Brain or actor identity changed");
            var value = new JsonObject();
            value.addProperty(
                "profile",
                runtime.getProfile()
                    .getId());
            value.addProperty("ready", runtime.isReady());
            value.addProperty("afterNativeNavigation", runtime.requiresAfterNativeNavigation());
            value.addProperty(
                "developmentOnly",
                runtime.getProfile()
                    .isDevelopmentOnly());
            value.addProperty(
                "contextMaidUuid",
                runtime.getContext()
                    .getMaidId()
                    .toString());
            value.addProperty(
                "epoch",
                runtime.getContext()
                    .getEpoch());
            value.addProperty("bindingStable", stable);
            value.addProperty("brainClass", (enhanced == null ? core.getClass() : enhanced.getClass()).getName());
            value.addProperty(
                "flightProfile",
                actor().rangedBow()
                    .flightProfile()
                    .name());
            if (enhanced != null) value.addProperty("independentCoreDecisions", enhanced.decisions());
            return value;
        }

        private ModernFollowCoreActor actor() {
            return enhanced == null ? core.getActor() : enhanced.actor();
        }

        private static MaidBrainProfile requestedProfile() {
            String profile = System.getProperty("tlmnh.qaNaturalProfile", "");
            if (profile.equals("enhanced_core_component")) return MaidBrainProfile.ENHANCED_CORE_COMPONENT;
            if (profile.equals("faithful_tlm")) return MaidBrainProfile.FAITHFUL_TLM;
            if (!profile.isEmpty()) throw new IllegalArgumentException("Explicit natural profile unsupported");
            return Boolean.getBoolean("tlmnh.qaFaithfulProfile") ? MaidBrainProfile.FAITHFUL_TLM
                : MaidBrainProfile.FOLLOW_CORE_COMPONENT;
        }

        private void moveOwner(int at) {
            var keys = scene().getAsJsonArray("ownerKeyframes");
            var start = keys.get(0)
                .getAsJsonObject();
            var end = keys.get(keys.size() - 1)
                .getAsJsonObject();
            for (int i = 1; i < keys.size(); i++) if (at <= keys.get(i)
                .getAsJsonObject()
                .get("frame")
                .getAsInt()) {
                    start = keys.get(i - 1)
                        .getAsJsonObject();
                    end = keys.get(i)
                        .getAsJsonObject();
                    break;
                }
            int a = start.get("frame")
                .getAsInt(),
                b = end.get("frame")
                    .getAsInt();
            double fraction = a == b ? 0 : Math.max(0, Math.min(1, (double) (at - a) / (b - a)));
            var from = start.getAsJsonArray("position");
            var to = end.getAsJsonArray("position");
            plannedX = fixture.x + from.get(0)
                .getAsDouble()
                + (to.get(0)
                    .getAsDouble()
                    - from.get(0)
                        .getAsDouble())
                    * fraction;
            plannedY = fixture.y + 1
                + from.get(1)
                    .getAsDouble()
                + (to.get(1)
                    .getAsDouble()
                    - from.get(1)
                        .getAsDouble())
                    * fraction;
            plannedZ = fixture.z + from.get(2)
                .getAsDouble()
                + (to.get(2)
                    .getAsDouble()
                    - from.get(2)
                        .getAsDouble())
                    * fraction;
            fixture.owner.setPosition(plannedX, plannedY, plannedZ);
            fixture.owner.motionX = fixture.owner.motionY = fixture.owner.motionZ = 0;
        }

        private void removeMaid() {
            if (maid == null) return;
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(maid);
            helper.assertFalse(helper.getWorld().loadedEntityList.contains(maid), "Natural actor retained");
            maid = null;
        }

        void close() {
            if (closed) return;
            closed = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            removeMaid();
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doDaylightCycle", daylight);
            clearTerrain();
            if (prepared) {
                for (var block : outside) helper.getWorld()
                    .setBlock(block.x, block.y, block.z, block.block, block.metadata, 2);
                for (int x = 0; x < 21; x++)
                    for (int z = 0; z < 9; z++) for (int y = 0; y <= 10; y++) helper.setBlock(x, y, z, Blocks.air);
            }
            output.addProperty("totalFrames", total);
            output.addProperty("complete", complete);
            output.addProperty("ownCleanupComplete", true);
            output.addProperty("parentFixtureCleanupNeedsHqaResult", true);
            try {
                var path = Paths.get(
                    System.getProperty("horizonqa.reportDir"),
                    water ? "follow-natural-water-port.json.gz" : "follow-natural-port.json.gz");
                try (var writer = new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(path)),
                    StandardCharsets.UTF_8)) {
                    new Gson().toJson(output, writer);
                }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Natural port observation could not be retained", failure);
            }
        }

        private static JsonArray vector(double x, double y, double z) {
            var array = new JsonArray();
            array.add(new com.google.gson.JsonPrimitive(x));
            array.add(new com.google.gson.JsonPrimitive(y));
            array.add(new com.google.gson.JsonPrimitive(z));
            return array;
        }

        private static final class SavedBlock {

            final int x, y, z, metadata;
            final Block block;

            SavedBlock(int x, int y, int z, Block block, int metadata) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.block = block;
                this.metadata = metadata;
            }
        }
    }
}
