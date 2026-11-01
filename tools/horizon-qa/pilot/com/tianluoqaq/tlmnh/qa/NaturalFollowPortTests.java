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
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreComponent;
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
        var collector = new Collector(helper);
        FMLCommonHandler.instance()
            .bus()
            .register(collector);
        helper.onEachTick(
            "natural observer failure delivery",
            () -> { if (collector.failure != null) throw collector.failure; });
        helper.succeedWhen(() -> collector.complete);
    }

    public static final class Collector {

        private static final int[] LENGTHS = { 200, 240, 160 };
        private static final String[] IDS = { "N01_straight_stop", "N02_right_angle", "N03_teleport_boundary" };
        private static final double[][][] KEYS = {
            { { 0, 7.5, .5 }, { 39, 7.5, .5 }, { 119, 13.1, .5 }, { 199, 13.1, .5 } },
            { { 0, 7.5, .5 }, { 39, 7.5, .5 }, { 99, 11.7, .5 }, { 159, 11.7, 4.7 }, { 239, 11.7, 4.7 } },
            { { 0, 7.5, .5 }, { 39, 7.5, .5 }, { 40, 21.5, .5 }, { 159, 21.5, .5 } } };

        final GameTestHelper helper;
        final ModernCoreTests.Fixture fixture;
        final JsonObject output = new JsonObject();
        final JsonArray runs = new JsonArray();
        final String daylight;
        final List<SavedBlock> outside = new ArrayList<>();
        EntityMaid maid;
        ModernFollowCoreComponent core;
        JsonArray frames;
        JsonObject row;
        int caseIndex, repetition, warmup, frame, total;
        boolean complete, closed;
        boolean prepared;
        RuntimeException failure;
        double plannedX, plannedZ;

        Collector(GameTestHelper helper) {
            this.helper = helper;
            fixture = new ModernCoreTests.Fixture(helper, false, 1000, 8);
            fixture.maid.getBrainCoordinator()
                .setSuspended(true);
            fixture.maid.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(fixture.maid);
            daylight = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            helper.afterTest(this::close);
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
            output.addProperty("schema", "follow-natural-port-observation-v1");
            output.addProperty("contract", "FOLLOW_NATURAL_O_V1");
            output.addProperty("candidateIsCompleteFaithfulProfile", false);
            output.addProperty("originalEquivalencePassed", false);
            output.addProperty("statisticalAdmission", false);
            output.addProperty("worldRngAndSensorPhaseUncontrolled", true);
            output.addProperty(
                "ownerKind",
                "standard Forge FakePlayer in online/world tables, synthetic position inputs");
            output.addProperty("sourceFlightProfile", "SOURCE_ORIGINAL (no bow in these scenes)");
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
            moveOwner(0);
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (failure != null) return;
            try {
                observe(event);
            } catch (RuntimeException exception) {
                failure = exception;
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
                    && Math.abs(fixture.owner.posY - (fixture.y + 1)) < .001,
                "Natural owner input did not land");
            var sample = new JsonObject();
            sample.addProperty("index", frame);
            sample.addProperty("age", maid.ticksExisted);
            sample.add("position", vector(maid.posX - fixture.x, maid.posY - fixture.y - 1, maid.posZ - fixture.z));
            sample.add("motion", vector(maid.motionX, maid.motionY, maid.motionZ));
            sample.add("owner", vector(fixture.owner.posX - fixture.x, 0, fixture.owner.posZ - fixture.z));
            sample.addProperty("onGround", maid.onGround);
            sample.addProperty("health", maid.getHealth());
            sample.addProperty(
                "activity",
                core.getActivities()
                    .active()
                    .name());
            sample.addProperty(
                "speed",
                maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue());
            sample.addProperty(
                "teleportAttempts",
                core.getActor()
                    .teleportAttempts());
            var path = maid.getNavigator()
                .getPath();
            sample.addProperty("pathPresent", path != null);
            if (path != null) {
                sample.addProperty("pathIndex", path.getCurrentPathIndex());
                sample.addProperty("pathLength", path.getCurrentPathLength());
            }
            frames.add(sample);
            total++;
            if (++frame == LENGTHS[caseIndex]) {
                row.addProperty("complete", true);
                removeMaid();
                row.addProperty("actorRemoved", true);
                System.out.println("TLMNH_NATURAL_PORT_RUN case=" + IDS[caseIndex] + " repetition=" + repetition);
                if (++repetition == 3) {
                    repetition = 0;
                    caseIndex++;
                }
                if (caseIndex == IDS.length) complete = true;
                else {
                    warmup = 0;
                    moveOwner(0);
                }
            }
        }

        private void spawn() {
            maid = new EntityMaid(helper.getWorld());
            helper.assertTrue(maid.bindTo(fixture.owner.getUniqueID()), "Natural owner binding rejected");
            maid.setSchedule(MaidSchedule.ALL);
            helper.spawnEntity(maid, .5, 1, .5);
            core = new ModernFollowCoreComponent(maid);
            maid.getBrainCoordinator()
                .setAfterNavigationActionsForDevelopment(core);
            core.getActor()
                .rangedBow()
                .setFlightProfileForDevelopment(ModernFlightProfile.SOURCE_ORIGINAL);
            frame = 0;
            frames = new JsonArray();
            row = new JsonObject();
            row.addProperty("id", IDS[caseIndex]);
            row.addProperty("rebuild", repetition);
            row.addProperty(
                "maidUuid",
                maid.getUniqueID()
                    .toString());
            row.addProperty(
                "ownerUuid",
                fixture.owner.getUniqueID()
                    .toString());
            row.addProperty("pickup", maid.isPickup());
            row.addProperty("complete", false);
            row.addProperty("actorRemoved", false);
            row.add("frames", frames);
            runs.add(row);
        }

        private void moveOwner(int at) {
            var keys = KEYS[caseIndex];
            var start = keys[0];
            var end = keys[keys.length - 1];
            for (int i = 1; i < keys.length; i++) if (at <= keys[i][0]) {
                start = keys[i - 1];
                end = keys[i];
                break;
            }
            double fraction = end[0] == start[0] ? 0 : (at - start[0]) / (end[0] - start[0]);
            plannedX = fixture.x + start[1] + (end[1] - start[1]) * fraction;
            plannedZ = fixture.z + start[2] + (end[2] - start[2]) * fraction;
            fixture.owner.setPosition(plannedX, fixture.y + 1, plannedZ);
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
                var path = Paths.get(System.getProperty("horizonqa.reportDir"), "follow-natural-port.json.gz");
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
