package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 三机制O/F/N合同的完整工厂入口；复用已有正常native登录、逐世界时序与保类型输入采集 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ActivityCompetitionPortTests {

    private ActivityCompetitionPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "activity_wait_f")
    public static void faithfulWaitingThreatFood(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "waiting_threat_food");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "activity_wait_n")
    public static void enhancedWaitingThreatFood(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "waiting_threat_food");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 360, batch = "activity_air_f")
    public static void faithfulNaturalMealLowAir(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "meal_low_air");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 360, batch = "activity_air_n")
    public static void enhancedNaturalMealLowAir(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "meal_low_air");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1060, batch = "activity_cake_f")
    public static void faithfulSharedCakeInterruption(GameTestHelper h) {
        collect(h, MaidBrainProfile.FAITHFUL_TLM, "shared_cake_interruption");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1060, batch = "activity_cake_n")
    public static void enhancedSharedCakeInterruption(GameTestHelper h) {
        collect(h, MaidBrainProfile.ENHANCED, "shared_cake_interruption");
    }

    private static void collect(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
        var c = new Collector(h, profile, mechanism);
        h.afterTest(c::close);
        h.onEachTick("activity competition capture failures", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
        c.setup();
    }

    public static class Collector extends FollowMechanismPortTests.Collector {

        final String mechanism;
        EntityMaid second;
        EntityCreeper threat;
        int secondLiving, secondAge, triggerAt = -1, releaseAt = -1, previousBites;
        boolean extrasClosed;

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            this(
                h,
                profile,
                mechanism,
                "/tlmnh-activity-competition/contract.json",
                "ACTIVITY_COMPETITION_V1",
                "activity-competition-port-v1",
                "activity-competition-");
        }

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism, String resource,
            String expectedContract, String schema, String prefix) {
            super(h, profile, mechanism, resource, expectedContract, schema, prefix);
            this.mechanism = mechanism;
        }

        @Override
        void setup() {
            super.setup();
            for (int a = 0; a < 25; a++) for (int b = 0; b < 8; b++)
                for (int c = 0; c < 25; c++) helper.setBlock(a, b, c, b == 0 ? Blocks.stone : Blocks.air);
            try {
                output.add(
                    "observerArtifact",
                    DevelopmentArtifactIdentity.capture(
                        ActivityCompetitionPortTests.class,
                        Collector.class,
                        FollowMechanismPortTests.class,
                        FollowMechanismPortTests.Collector.class,
                        SleepAttributePortSnapshot.class));
            } catch (Exception failure) {
                throw new IllegalStateException("Activity observation identity unavailable", failure);
            }
            output.addProperty(
                "inputSchedule",
                "fixed events and reactive inputs occur only after the recorded natural END frame");
            output.addProperty("crossEngineFullNbtEqualityRequired", false);
        }

        @Override
        void beforeSpawn() {
            output.addProperty(
                "actualPreparedRestrictionRadius",
                maid.getHomeArea()
                    .restrictionRadius());
            output.addProperty(
                "expectedPreparedRestrictionRadius",
                contract.get("restrictionRadius")
                    .getAsInt());
            super.beforeSpawn();
            maid.setHealth(
                scene.get("health")
                    .getAsFloat());
            if (mechanism.equals("meal_low_air")) {
                maid.getMaidInventory()
                    .setInventorySlotContents(0, new ItemStack(Items.mushroom_stew));
                output.addProperty("initialNonEmptyItemStackCount", 1);
                output.addProperty(
                    "actualFoodDuration",
                    Items.mushroom_stew.getMaxItemUseDuration(new ItemStack(Items.mushroom_stew)));
                output.addProperty(
                    "actualFoodNutrition",
                    com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile
                        .inspect(new ItemStack(Items.mushroom_stew)).total);
            } else if (mechanism.equals("waiting_threat_food")) {
                threat = new EntityCreeper(helper.getWorld());
                threat.func_110163_bv();
                threat.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .setBaseValue(0);
                helper.spawnEntity(threat, 16.5, 1, 12.5);
                helper.assertTrue(
                    threat.isNoDespawnRequired() && helper.getWorld()
                        .getEntityByID(threat.getEntityId()) == threat,
                    "Controlled native persistent threat was replaced or unregistered");
                var input = raw(threat);
                input.addProperty("persistent", threat.isNoDespawnRequired());
                input.addProperty(
                    "movementBase",
                    threat.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getBaseValue());
                input.addProperty(
                    "movementValue",
                    threat.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                        .getAttributeValue());
                input.addProperty("health", threat.getHealth());
                input.addProperty("maxHealth", threat.getMaxHealth());
                output.add("threatInitial", input);
            } else {
                maid.setPosition(x + 10.5, y + 1, z + 12.5);
                helper.setBlock(12, 1, 12, MaidLifeContent.snackCabinet);
                helper.setBlock(12, 2, 12, Blocks.cake);
                second = new EntityMaid(helper.getWorld());
                helper.assertTrue(second.bindTo(owner.getUniqueID()), "Second natural owner binding failed");
                second.setTask(MaidTask.IDLE);
                second.setSchedule(MaidSchedule.ALL);
                second.setHunger(0);
                second.setPickup(false);
                second.setModelId(
                    contract.get("model")
                        .getAsString());
                DeterministicMaidSpawn.freezeInitialState(second);
                helper.spawnEntity(second, 15.5, 1, 12.5);
                var selected = second.selectBrainProfileForDevelopment(profile);
                helper.assertTrue(
                    (selected == MaidPolicyRuntime.SelectionResult.SELECTED
                        || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED)
                        && second.getBrainCoordinator()
                            .peekPolicyRuntime()
                            .getProfile() == profile,
                    "Second complete root selection failed or actual profile differs");
                output.add("secondInitial", raw(second));
                output.add("secondAttributeInputs", SleepAttributePortSnapshot.capture(second));
            }
        }

        @Override
        void onLiving(Entity entity) {
            super.onLiving(entity);
            if (entity == second) secondLiving++;
        }

        @Override
        int frameLimit() {
            return scene.get("frames")
                .getAsInt();
        }

        @Override
        void spawn() {
            super.spawn();
            output.add("initialActorState", state(maid));
            if (second != null) output.add("secondInitialState", state(second));
        }

        @Override
        void afterFrame(int index) {
            var row = frames.get(index)
                .getAsJsonObject();
            row.add("maid", state(maid));
            if (second != null) {
                helper.assertTrue(
                    second.ticksExisted == secondAge + 1 && secondLiving == 1
                        && second.getOwner() == owner
                        && second.getBrainCoordinator()
                            .peekPolicyRuntime()
                            .getProfile() == profile,
                    "Second actor skipped a natural tick or changed full root");
                secondAge = second.ticksExisted;
                secondLiving = 0;
                row.add("second", state(second));
            }
            if (threat != null) {
                row.addProperty(
                    "threatRegistered",
                    helper.getWorld()
                        .getEntityByID(threat.getEntityId()) == threat);
                row.addProperty("threatDistanceSquared", maid.getDistanceSqToEntity(threat));
            }
            if (mechanism.equals("waiting_threat_food")) {
                for (var spec : scene.getAsJsonArray("events")) {
                    var e = spec.getAsJsonObject();
                    if (index != e.get("frame")
                        .getAsInt()) continue;
                    var event = event(
                        index,
                        e.get("kind")
                            .getAsString());
                    if (e.get("kind")
                        .getAsString()
                        .equals("remove_threat")) threat.setDead();
                    else {
                        maid.setSitting(false);
                        setPlannedOwner(e.getAsJsonArray("ownerPosition"));
                    }
                    event.addProperty("sittingAfter", maid.isSitting());
                }
            } else if (mechanism.equals("meal_low_air")) {
                helper.assertTrue(
                    count(maid, Items.mushroom_stew) + count(maid, Items.bowl) == 1,
                    "Natural meal lost or duplicated its only soup/container");
                if (triggerAt < 0 && maid.getMealController()
                    .isEating()) {
                    triggerAt = index;
                    event(index, "natural_meal_low_air");
                    water(true);
                    maid.setAir(60);
                } else if (triggerAt >= 0 && releaseAt < 0 && index == triggerAt + 40) {
                    releaseAt = index;
                    event(index, "clear_water_input");
                    water(false);
                }
            } else cakeFrame(index, row);
            if (index + 1 == frameLimit()) {
                output.addProperty("reactiveTriggerFrame", triggerAt);
                output.addProperty("reactiveReleaseFrame", releaseAt);
                output.addProperty(
                    "scenarioInputExercised",
                    mechanism.equals("waiting_threat_food") || triggerAt >= 0 && releaseAt >= 0);
                output.addProperty("resourceAssertionsPassed", true);
                output.addProperty(
                    "resourceAssertionScope",
                    mechanism.equals("meal_low_air") ? "one_soup_or_bowl_total_each_frame"
                        : mechanism.equals("shared_cake_interruption") ? "one_native_cake_monotonic_bites_zero_to_seven"
                            : "no_material_assertion_dropped_apple_may_be_naturally_consumed");
            }
        }

        void cakeFrame(int index, JsonObject row) {
            int bites = cakeBites();
            row.addProperty("cakeBitesConsumed", bites);
            helper.assertTrue(
                bites >= previousBites && bites <= 7 && bites - previousBites <= 2,
                "One shared native cake produced an impossible per-tick bite change");
            previousBites = bites;
            if (triggerAt < 0 && bites > 0) {
                triggerAt = index;
                event(index, "first_shared_cake_bite_wait");
                maid.setSitting(true);
                second.setSitting(true);
            } else if (triggerAt >= 0 && releaseAt < 0 && index == triggerAt + 40) {
                releaseAt = index;
                event(index, "release_both_waiting");
                maid.setSitting(false);
                second.setSitting(false);
            }
        }

        JsonObject event(int index, String kind) {
            var row = new JsonObject();
            row.addProperty("frame", index);
            row.addProperty("kind", kind);
            row.addProperty("phase", "END_after_recorded_frame");
            row.add("maidBefore", state(maid));
            if (second != null) row.add("secondBefore", state(second));
            events.add(row);
            return row;
        }

        void water(boolean present) {
            // 固定当前身体输入，清除时使用同一合同区域；不移动演员、不手动刷新空气或Brain
            for (int a = 11; a <= 13; a++) for (int b = 1; b <= 3; b++) for (int c = 11; c <= 13; c++) helper.getWorld()
                .setBlock(x + a, y + b, z + c, present ? Blocks.water : Blocks.air, 0, 2);
        }

        JsonObject state(EntityMaid actor) {
            var r = new JsonObject();
            r.addProperty("age", actor.ticksExisted);
            r.addProperty("health", actor.getHealth());
            r.addProperty("air", actor.getAir());
            r.addProperty("hunger", actor.getHunger());
            r.addProperty("favorability", actor.getFavorability());
            r.addProperty("sitting", actor.isSitting());
            r.addProperty("inWater", actor.isInWater());
            r.addProperty(
                "goingToBreath",
                actor.getAquaticController()
                    .getManager()
                    .isGoingToBreath());
            r.addProperty(
                "usingFood",
                actor.getMealController()
                    .isEating());
            r.addProperty("soup", count(actor, Items.mushroom_stew));
            r.addProperty("bowls", count(actor, Items.bowl));
            r.addProperty("apples", count(actor, Items.apple));
            r.add("position", vector(actor.posX - x, actor.posY - y, actor.posZ - z));
            if (actor.ticksExisted == 0) {
                // 注册初态还没有完整根的自然frame，不能借观察建立或伪造activity授权
                r.addProperty("activity", "NOT_SAMPLED_BEFORE_FIRST_NATURAL_FRAME");
                r.addProperty("activityAvailable", false);
                return r;
            }
            r.addProperty("activityAvailable", true);
            r.addProperty(
                "activity",
                profile == MaidBrainProfile.ENHANCED ? String.valueOf(
                    actor.getBrainCoordinator()
                        .peekEnhancedCore()
                        .selectedKind())
                    : actor.getBrainCoordinator()
                        .peekFaithfulBrain()
                        .getActivities()
                        .active()
                        .name());
            return r;
        }

        int count(EntityMaid actor, net.minecraft.item.Item item) {
            int n = 0;
            var inventory = actor.getMaidInventory();
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                var stack = inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() == item) n += stack.stackSize;
            }
            return n;
        }

        int cakeBites() {
            return helper.getWorld()
                .getBlock(x + 12, y + 2, z + 12) == Blocks.cake ? helper.getWorld()
                    .getBlockMetadata(x + 12, y + 2, z + 12) : 7;
        }

        JsonObject raw(Entity entity) {
            var tag = new NBTTagCompound();
            entity.writeToNBT(tag);
            var r = new JsonObject();
            r.addProperty(
                "class",
                entity.getClass()
                    .getName());
            r.addProperty(
                "uuid",
                entity.getUniqueID()
                    .toString());
            r.add("position", vector(entity.posX - x, entity.posY - y, entity.posZ - z));
            r.addProperty("rawNBT", tag.toString());
            r.add("binaryNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) tag.copy()));
            return r;
        }

        @Override
        JsonArray layout() {
            var out = new JsonArray();
            for (int a = 0; a < 25; a++) for (int b = 0; b < 8; b++) for (int c = 0; c < 25; c++) {
                var block = helper.getWorld()
                    .getBlock(x + a, y + b, z + c);
                if (block == Blocks.air) continue;
                var r = new JsonObject();
                r.addProperty("x", a);
                r.addProperty("y", b);
                r.addProperty("z", c);
                r.addProperty(
                    "semantic",
                    block == Blocks.stone ? "stone"
                        : block == Blocks.cake ? "cake"
                            : block == MaidLifeContent.snackCabinet ? "snack_cabinet" : "unknown");
                r.addProperty("registry", net.minecraft.block.Block.blockRegistry.getNameForObject(block));
                r.addProperty(
                    "metadata",
                    helper.getWorld()
                        .getBlockMetadata(x + a, y + b, z + c));
                out.add(r);
            }
            return out;
        }

        @Override
        void close() {
            if (closed || extrasClosed) return;
            extrasClosed = true;
            try {
                if (second != null) {
                    second.getBrainCoordinator()
                        .setSuspended(true);
                    helper.getWorld()
                        .removeEntity(second);
                }
                if (threat != null) {
                    threat.setDead();
                    helper.getWorld()
                        .removeEntity(threat);
                }
                for (int a = 0; a < 25; a++)
                    for (int b = 0; b < 8; b++) for (int c = 0; c < 25; c++) helper.setBlock(a, b, c, Blocks.air);
                output.addProperty("extraCleanupComplete", true);
            } finally {
                super.close();
            }
        }
    }
}
