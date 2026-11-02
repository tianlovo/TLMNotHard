package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 自然完整F/N的五机制输入采集，单次探索不是正式B10或统计准入 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SleepMechanismPortTests {

    private SleepMechanismPortTests() {}

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_faithful_normal")
    public static void faithfulNormal(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.FAITHFUL_TLM, "normal");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_faithful_occupied_release")
    public static void faithfulOccupiedRelease(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.FAITHFUL_TLM, "occupied_release");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_faithful_unreachable")
    public static void faithfulUnreachable(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.FAITHFUL_TLM, "unreachable");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_faithful_bed_removed")
    public static void faithfulBedRemoved(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.FAITHFUL_TLM, "bed_removed");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_faithful_hurt_sleeping")
    public static void faithfulHurtSleeping(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.FAITHFUL_TLM, "hurt_sleeping");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_enhanced_normal")
    public static void enhancedNormal(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.ENHANCED, "normal");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_enhanced_occupied_release")
    public static void enhancedOccupiedRelease(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.ENHANCED, "occupied_release");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_enhanced_unreachable")
    public static void enhancedUnreachable(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.ENHANCED, "unreachable");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_enhanced_bed_removed")
    public static void enhancedBedRemoved(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.ENHANCED, "bed_removed");
    }

    @com.gtnewhorizons.horizonqa.api.annotation.GameTest(
        template = "modern_core_cell",
        timeoutTicks = 330,
        batch = "sleep_pair_enhanced_hurt_sleeping")
    public static void enhancedHurtSleeping(GameTestHelper helper) {
        NaturalSleepPortTests.collectMechanism(helper, MaidBrainProfile.ENHANCED, "hurt_sleeping");
    }

    public static final class Collector extends NaturalSleepPortTests.Collector {

        final String mechanism;
        final boolean oldRain, oldThunder;
        final int oldRainTime, oldThunderTime;
        EntityMaid holder;
        boolean applied;
        int firstSleep = -1;

        Collector(GameTestHelper helper, MaidBrainProfile profile, String mechanism) {
            super(
                helper,
                profile,
                "/tlmnh-sleep/paired-contract.json",
                "SLEEP_PAIRED_MECHANISMS_V1",
                "sleep-paired-" + profile.getId() + "-" + mechanism + ".json");
            this.mechanism = mechanism;
            var info = helper.getWorld()
                .getWorldInfo();
            oldRain = info.isRaining();
            oldThunder = info.isThundering();
            oldRainTime = info.getRainTime();
            oldThunderTime = info.getThunderTime();
            output.addProperty("schema", "sleep-paired-port-mechanisms-v1");
            output.addProperty("mechanism", mechanism);
        }

        @Override
        void setup() {
            super.setup();
            helper.assertTrue(
                contract.get("repetitions")
                    .getAsInt() == 1,
                "This exploration requires one fresh actor per scene");
            try {
                output.add(
                    "pairedObserverArtifact",
                    DevelopmentArtifactIdentity.capture(SleepMechanismPortTests.class, Collector.class));
            } catch (Exception error) {
                throw new IllegalStateException("Paired observer identity unavailable", error);
            }
            try {
                output.add(
                    "sleepOwnerArtifact",
                    DevelopmentArtifactIdentity.capture(
                        com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator.class,
                        Class.forName(
                            "com.tianluoqaq.tlmnh.common.ai.faithful.MaidModernLife",
                            false,
                            EntityMaid.class.getClassLoader())));
            } catch (Exception error) {
                throw new IllegalStateException("Sleep owner identity unavailable", error);
            }
            helper.assertTrue(
                helper.getWorld().difficultySetting.name()
                    .equals(
                        contract.get("difficulty")
                            .getAsString()),
                "Actual native difficulty differs from paired contract");
            var info = helper.getWorld()
                .getWorldInfo();
            info.setRaining(false);
            info.setThundering(false);
            info.setRainTime(1000000);
            info.setThunderTime(1000000);
            if (mechanism.equals("unreachable")) {
                for (int a = 4; a <= 10; a++) for (int b = 1; b <= 4; b++) for (int c = 1; c <= 7; c++)
                    if (!(a == 7 && (c == 4 || c == 5))) helper.setBlock(a, b, c, Blocks.stone);
            }
            if (mechanism.equals("occupied_release")) {
                holder = new EntityMaid(helper.getWorld());
                helper.assertTrue(holder.bindTo(offlineOwner), "Reservation holder bind failed");
                holder.getBrainCoordinator()
                    .setSuspended(true);
                helper.spawnEntity(holder, 15.5, 1, 7.5);
                helper.assertTrue(bed.claim(holder), "Initial real bed reservation failed");
            }
        }

        @SubscribeEvent
        public void beforeWorld(TickEvent.WorldTickEvent event) {
            if (event.phase != TickEvent.Phase.START || event.world != helper.getWorld()
                || closed
                || holder == null
                || applied) return;
            helper.assertTrue(bed.claim(holder), "Controlled bed reservation was overwritten before release");
        }

        @Override
        protected void afterSpawn() {
            var initial = new JsonObject();
            initial.addProperty("width", maid.width);
            initial.addProperty("height", maid.height);
            initial.addProperty("health", maid.getHealth());
            initial.addProperty("maxHealth", maid.getMaxHealth());
            initial.addProperty(
                "movementSpeed",
                maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue());
            initial.addProperty("model", maid.getModelId());
            initial.addProperty(
                "task",
                maid.getTask()
                    .getId());
            initial.addProperty("hunger", maid.getHunger());
            initial.addProperty("favorability", maid.getFavorability());
            initial.addProperty("pickup", maid.isPickup());
            initial.addProperty("difficulty", helper.getWorld().difficultySetting.name());
            initial.addProperty(
                "raining",
                helper.getWorld()
                    .isRaining());
            initial.addProperty(
                "thundering",
                helper.getWorld()
                    .isThundering());
            var raw = new NBTTagCompound();
            maid.writeToNBT(raw);
            initial.addProperty("rawInitialNBT", raw.toString());
            var blocks = new JsonArray();
            for (int a = 0; a < 17; a++) for (int b = 0; b < 8; b++) for (int c = 0; c < 9; c++) {
                var block = helper.getWorld()
                    .getBlock(x + a, y + b, z + c);
                if (block == Blocks.air) continue;
                int meta = helper.getWorld()
                    .getBlockMetadata(x + a, y + b, z + c);
                var value = new JsonObject();
                value.addProperty("x", a);
                value.addProperty("y", b);
                value.addProperty("z", c);
                value.addProperty("registry", net.minecraft.block.Block.blockRegistry.getNameForObject(block));
                value.addProperty("metadata", meta);
                value.addProperty(
                    "semantic",
                    block == Blocks.stone ? "stone"
                        : block == MaidLifeContent.maidBed
                            ? (meta & 8) != 0 ? "maid_bed_head_north" : "maid_bed_foot_north"
                            : "unknown");
                blocks.add(value);
            }
            int nonEmpty = 0;
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                if (inventory.getStackInSlot(slot) != null) nonEmpty++;
            initial.addProperty("nonEmptyItemStackCount", nonEmpty);
            initial.addProperty(
                "activeEffectCount",
                maid.getActivePotionEffects()
                    .size());
            helper.assertTrue(
                nonEmpty == 0 && maid.getActivePotionEffects()
                    .isEmpty(),
                "Fresh paired native actor has items/effects");
            initial.add("nonAirBlocks", blocks);
            var row = runs.get(runs.size() - 1)
                .getAsJsonObject();
            row.add("actualInputs", initial);
            row.add("events", new JsonArray());
        }

        @Override
        protected void afterFrame(JsonObject sample) {
            var coordinator = maid.getBrainCoordinator();
            boolean enhanced = profile == MaidBrainProfile.ENHANCED;
            helper.assertTrue(
                (enhanced ? coordinator.peekEnhancedCore() != null && coordinator.peekFaithfulBrain() == null
                    : coordinator.peekFaithfulBrain() != null && coordinator.peekEnhancedCore() == null)
                    && coordinator.getFollowController()
                        .getTicks() == 0
                    && coordinator.getCombatController()
                        .getTicks() == 0,
                "Measured actor did not keep its complete selected root or ran Legacy controllers");
            sample.addProperty("fullRootAuthorized", true);
            sample.addProperty("width", maid.width);
            sample.addProperty("height", maid.height);
            boolean present = helper.getWorld()
                .getBlock(x + 7, y + 1, z + 4) == MaidLifeContent.maidBed;
            sample.addProperty("bedPresent", present);
            int frame = sample.get("frame")
                .getAsInt();
            if (sample.get("sleeping")
                .getAsBoolean() && firstSleep < 0) firstSleep = frame;
            boolean release = mechanism.equals("occupied_release") && frame == contract.get("releaseFrame")
                .getAsInt();
            boolean afterSleep = firstSleep >= 0 && frame == firstSleep + contract.get("afterFirstSleepingFrames")
                .getAsInt();
            if (applied
                || !(release || afterSleep && (mechanism.equals("bed_removed") || mechanism.equals("hurt_sleeping"))))
                return;
            applied = true;
            var event = new JsonObject();
            event.addProperty("frame", frame);
            event.addProperty("kind", mechanism);
            event.addProperty("preSleeping", maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING));
            event.addProperty("healthBefore", maid.getHealth());
            if (release) bed.release(holder);
            else if (mechanism.equals("bed_removed")) {
                helper.getWorld()
                    .setBlock(x + 7, y + 1, z + 4, Blocks.air, 0, 2);
                helper.getWorld()
                    .setBlock(x + 7, y + 1, z + 5, Blocks.air, 0, 2);
            } else event.addProperty(
                "damageAccepted",
                maid.attackEntityFrom(
                    DamageSource.generic,
                    contract.get("damage")
                        .getAsFloat()));
            event.addProperty("postSleeping", maid.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.SLEEPING));
            event.addProperty("healthAfter", maid.getHealth());
            event.addProperty(
                "postBedOccupied",
                (helper.getWorld()
                    .getBlockMetadata(x + 7, y + 1, z + 4) & 4) != 0);
            runs.get(runs.size() - 1)
                .getAsJsonObject()
                .getAsJsonArray("events")
                .add(event);
        }

        @Override
        void close() {
            try {
                if (holder != null) {
                    bed.release(holder);
                    holder.setDead();
                    helper.getWorld()
                        .removePlayerEntityDangerously(holder);
                    holder = null;
                }
                var info = helper.getWorld()
                    .getWorldInfo();
                info.setRaining(oldRain);
                info.setThundering(oldThunder);
                info.setRainTime(oldRainTime);
                info.setThunderTime(oldThunderTime);
                output.addProperty("controlledReservationAndWeatherRestored", true);
            } finally {
                super.close();
            }
        }
    }
}
