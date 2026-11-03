package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Four complete-root mechanism families; approach is a retained calibration contrast, not another feature. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class TerrainLifeMechanismPortTests {

    static final String RESOURCE = "/tlmnh-terrain-life-mechanisms/contract.json",
        CONTRACT = "TERRAIN_LIFE_MECHANISMS_V2";

    private TerrainLifeMechanismPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 600, batch = "terrain_circuit_f")
    public static void faithfulTerrainCircuit(GameTestHelper h) {
        run(h, false, "terrain_circuit");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 600, batch = "terrain_circuit_n")
    public static void enhancedTerrainCircuit(GameTestHelper h) {
        run(h, true, "terrain_circuit");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 600, batch = "terrain_fire_f")
    public static void faithfulFireCliff(GameTestHelper h) {
        run(h, false, "fire_cliff");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 600, batch = "terrain_fire_n")
    public static void enhancedFireCliff(GameTestHelper h) {
        run(h, true, "fire_cliff");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 480, batch = "terrain_creeper_f")
    public static void faithfulNativeCreeper(GameTestHelper h) {
        run(h, false, "native_creeper");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 480, batch = "terrain_creeper_n")
    public static void enhancedNativeCreeper(GameTestHelper h) {
        run(h, true, "native_creeper");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2320, batch = "terrain_pantry_f")
    public static void faithfulPantryNear(GameTestHelper h) {
        run(h, false, "pantry_near");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2320, batch = "terrain_pantry_n")
    public static void enhancedPantryNear(GameTestHelper h) {
        run(h, true, "pantry_near");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1120, batch = "terrain_approach_f")
    public static void faithfulPantryApproach(GameTestHelper h) {
        run(h, false, "pantry_approach");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1120, batch = "terrain_approach_n")
    public static void enhancedPantryApproach(GameTestHelper h) {
        run(h, true, "pantry_approach");
    }

    static void run(GameTestHelper h, boolean enhanced, String mechanism) {
        var profile = enhanced ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM;
        FollowMechanismPortTests.Collector c = mechanism.startsWith("pantry")
            ? new PantryCollector(h, profile, mechanism)
            : new Collector(h, profile, mechanism);
        h.afterTest(c::close);
        h.onEachTick("terrain/life natural collection", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
        c.setup();
    }

    static void identity(JsonObject output) {
        try {
            output.add(
                "terrainObserverArtifact",
                DevelopmentArtifactIdentity.capture(
                    TerrainLifeMechanismPortTests.class,
                    Collector.class,
                    PantryCollector.class,
                    NaturalRangedPairTests.class));
            output.add(
                "terrainProductionArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator.class,
                    com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedLifeController.class,
                    com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedPanicController.class,
                    com.tianluoqaq.tlmnh.common.life.MaidHomeArea.class,
                    com.tianluoqaq.tlmnh.common.ai.MaidCreeperAvoidance.class,
                    com.tianluoqaq.tlmnh.common.ai.MaidCreeperAvoidance.AvoidMaidGoal.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidMoveToBlockTask.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleMoveBlockTask.class,
                    com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleUseTask.class));
        } catch (Exception error) {
            throw new IllegalStateException("Terrain observer identity unavailable", error);
        }
    }

    static void enclose(GameTestHelper helper, int size, int height) {
        for (int a = 0; a < size; a++) for (int b = 1; b < height; b++)
            for (int c = 0; c < size; c++) if (a == 0 || a == size - 1 || c == 0 || c == size - 1 || b == height - 1)
                helper.setBlock(a, b, c, Blocks.glass);
    }

    static JsonObject raw(Entity entity, int x, int y, int z) {
        var row = new JsonObject();
        row.addProperty(
            "class",
            entity.getClass()
                .getName());
        row.addProperty(
            "uuid",
            entity.getUniqueID()
                .toString());
        row.addProperty("age", entity.ticksExisted);
        row.addProperty("alive", entity.isEntityAlive());
        row.addProperty("registered", entity.worldObj.getEntityByID(entity.getEntityId()) == entity);
        row.addProperty(
            "providerClass",
            entity.worldObj.provider.getClass()
                .getName());
        row.add("position", NaturalRangedPairTests.vector(entity.posX - x, entity.posY - y, entity.posZ - z));
        if (entity instanceof EntityLivingBase living) {
            row.addProperty("health", living.getHealth());
            row.add("physicalConditions", NaturalRangedPairTests.physicalConditions(living));
        }
        var tag = new NBTTagCompound();
        entity.writeToNBT(tag);
        row.add("binaryNBT", SleepAttributePortSnapshot.binaryNbt(tag));
        return row;
    }

    public static final class Collector extends FollowMechanismPortTests.Collector {

        static final java.lang.reflect.Field IGNITION = cpw.mods.fml.relauncher.ReflectionHelper
            .findField(EntityCreeper.class, "timeSinceIgnited", "field_70833_d");
        static final java.lang.reflect.Field FUSE = cpw.mods.fml.relauncher.ReflectionHelper
            .findField(EntityCreeper.class, "fuseTime", "field_82225_f");

        final String mechanism;
        final JsonArray damage = new JsonArray();
        EntityCreeper creeper;
        int waypoint, reachedAt = -1;
        String oldFire, oldGrief;

        Collector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            super(h, profile, mechanism, RESOURCE, CONTRACT, "terrain-life-port-v1", "terrain-life-");
            this.mechanism = mechanism;
        }

        @Override
        void setup() {
            super.setup();
            try {
                chunks.forceChunksStrict(helper.getWorld(), x, y, z, x + 33, y + 12, z + 33);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot prepare complete terrain arena chunks", error);
            }
            identity(output);
            output.add("damageEvents", damage);
            var world = helper.getWorld();
            oldFire = world.getGameRules()
                .getGameRuleStringValue("doFireTick");
            oldGrief = world.getGameRules()
                .getGameRuleStringValue("mobGriefing");
            world.getGameRules()
                .setOrCreateGameRule("doFireTick", "false");
            world.getGameRules()
                .setOrCreateGameRule("mobGriefing", "false");
            for (int a = 0; a < 33; a++) for (int b = 0; b < 12; b++)
                for (int c = 0; c < 33; c++) helper.setBlock(a, b, c, b == 0 ? Blocks.stone : Blocks.air);
            if (mechanism.equals("terrain_circuit")) {
                for (int a = 10; a <= 13; a++)
                    for (int c = 10; c <= 14; c++) helper.setBlock(a, 1, c, Blocks.stone_slab);
                for (int a = 14; a <= 24; a++) for (int c = 10; c <= 14; c++) helper.setBlock(a, 1, c, Blocks.stone);
                for (int a = 18; a <= 24; a++) for (int c = 15; c <= 19; c++) helper.setBlock(a, 1, c, Blocks.stone);
                for (int a = 16; a <= 19; a++) for (int b = 2; b <= 4; b++) {
                    helper.setBlock(a, b, 11, Blocks.stone);
                    helper.setBlock(a, b, 13, Blocks.stone);
                }
            } else if (mechanism.equals("fire_cliff")) {
                for (int a = 0; a < 33; a++) for (int c = 0; c < 33; c++)
                    if (!(a >= 10 && a <= 14 && c >= 8 && c <= 16)) helper.setBlock(a, 8, c, Blocks.stone);
                for (int a = 5; a <= 6; a++) for (int c = 11; c <= 13; c++) helper.setBlock(a, 9, c, Blocks.fire);
            }
            enclose(helper, 33, 12);
            setPlannedOwner(
                scene.getAsJsonArray("waypoints")
                    .get(0)
                    .getAsJsonArray());
            if (mechanism.equals("native_creeper")) {
                owner.setGameType(GameType.SURVIVAL);
                owner.capabilities.disableDamage = false;
                owner.capabilities.allowFlying = owner.capabilities.isFlying = true;
            }
            applyOwner();
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            var point = scene.getAsJsonArray("maidSpawn");
            maid.setPosition(
                x + point.get(0)
                    .getAsDouble(),
                y + point.get(1)
                    .getAsDouble(),
                z + point.get(2)
                    .getAsDouble());
            output.addProperty("actualStepHeight", maid.stepHeight);
            if (mechanism.equals("native_creeper")) {
                int protection = ((Integer) cpw.mods.fml.relauncher.ReflectionHelper
                    .getPrivateValue(EntityPlayerMP.class, owner, "field_147101_bU")).intValue();
                output.addProperty("ownerSpawnProtectionAtThreat", protection);
                helper.assertTrue(
                    protection <= 0 && owner.isEntityAlive() && !owner.capabilities.disableDamage,
                    "Natural owner spawn protection has not expired");
                creeper = new EntityCreeper(helper.getWorld());
                var p = scene.getAsJsonArray("threatSpawn");
                creeper.setPosition(
                    x + p.get(0)
                        .getAsDouble(),
                    y + p.get(1)
                        .getAsDouble(),
                    z + p.get(2)
                        .getAsDouble());
                creeper.func_110163_bv();
                output.add("threatBeforeJoin", raw(creeper, x, y, z));
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(creeper)
                        && helper.getWorld()
                            .getEntityByID(creeper.getEntityId()) == creeper,
                    "Native persistent Creeper rejected/replaced");
                boolean handled = net.minecraftforge.event.ForgeEventFactory.doSpecialSpawn(
                    creeper,
                    helper.getWorld(),
                    (float) creeper.posX,
                    (float) creeper.posY,
                    (float) creeper.posZ);
                if (!handled) creeper.onSpawnWithEgg(null);
                output.addProperty("nativeSpecialSpawnHandled", handled);
                output.add("threatInitial", raw(creeper, x, y, z));
                output.add("actualCreeperGoals", goals());
                output.addProperty(
                    "replacementMarker",
                    creeper.getEntityData()
                        .getByte("smi"));
                helper.assertTrue(
                    creeper.isEntityAlive() && creeper.isNoDespawnRequired()
                        && helper.getWorld()
                            .getEntityByID(creeper.getEntityId()) == creeper,
                    "Native initialization changed exact threat identity");
            }
        }

        @Override
        int frameLimit() {
            return scene.get("frames")
                .getAsInt();
        }

        @Override
        void observe() {
            if (!maid.isEntityAlive() || !owner.isEntityAlive()) {
                output.addProperty("terminalReason", !maid.isEntityAlive() ? "maid_death" : "owner_death");
                output.addProperty(
                    "terminalWorldTick",
                    helper.getWorld()
                        .getTotalWorldTime());
                output.add("terminalActor", raw(maid, x, y, z));
                output.add("terminalOwner", raw(owner, x, y, z));
                output.addProperty("behaviorSuccess", false);
                complete = true;
                close();
                return;
            }
            super.observe();
        }

        @Override
        void afterFrame(int index) {
            var row = frames.get(index)
                .getAsJsonObject();
            var points = scene.getAsJsonArray("waypoints");
            row.addProperty("waypoint", waypoint);
            row.addProperty("burning", maid.isBurning());
            row.addProperty("fallDistance", maid.fallDistance);
            row.addProperty("nativeOnGround", maid.onGround);
            var navigation = (com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator) maid.getNavigator();
            row.addProperty("actualAStarSearches", navigation.searchCount());
            row.addProperty("budgetDeferrals", navigation.budgetDeferrals());
            if (creeper != null) {
                row.addProperty("creeperAlive", creeper.isEntityAlive());
                row.addProperty(
                    "creeperRegistered",
                    helper.getWorld()
                        .getEntityByID(creeper.getEntityId()) == creeper);
                row.addProperty("creeperAge", creeper.ticksExisted);
                row.addProperty("creeperSwellDirection", creeper.getCreeperState());
                try {
                    int ignition = IGNITION.getInt(creeper), fuse = FUSE.getInt(creeper);
                    row.addProperty("creeperIgnitionTicks", ignition);
                    row.addProperty("creeperFuseTicks", fuse);
                    row.addProperty("creeperSwelling", (float) ignition / (float) (fuse - 2));
                    row.addProperty(
                        "creeperSwellingSource",
                        "server timeSinceIgnited/(fuseTime-2); no client interpolation API");
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("Native server Creeper fields unavailable", error);
                }
                row.addProperty(
                    "creeperTarget",
                    creeper.getAttackTarget() == null ? ""
                        : creeper.getAttackTarget()
                            .getUniqueID()
                            .toString());
                row.add("creeperPosition", vector(creeper.posX - x, creeper.posY - y, creeper.posZ - z));
                row.addProperty("dangerDistanceSquared", maid.getDistanceSqToEntity(creeper));
            } else if (waypoint < points.size()) {
                var point = points.get(waypoint)
                    .getAsJsonArray();
                if (maid.getDistanceSq(
                    x + point.get(0)
                        .getAsDouble(),
                    y + point.get(1)
                        .getAsDouble(),
                    z + point.get(2)
                        .getAsDouble())
                    <= 4) {
                    var event = new JsonObject();
                    event.addProperty("frame", index);
                    event.addProperty("kind", "owner_waypoint_reached");
                    event.addProperty("waypoint", waypoint++);
                    event.addProperty("phase", "END_after_recorded_frame");
                    events.add(event);
                    if (waypoint < points.size()) setPlannedOwner(
                        points.get(waypoint)
                            .getAsJsonArray());
                    else reachedAt = index;
                }
            }
            if (index + 1 == frameLimit()) {
                output.addProperty("terminalReason", "window_end");
                output.addProperty("arrivalFrame", reachedAt);
                output.addProperty("behaviorSuccess", creeper == null ? reachedAt >= 0 : maid.isEntityAlive());
                if (creeper != null) output.add("threatFinal", raw(creeper, x, y, z));
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving != maid && event.entityLiving != owner) return;
            var row = new JsonObject();
            row.addProperty("frame", frames.size());
            row.addProperty("victim", event.entityLiving == maid ? "maid" : "owner");
            row.addProperty("amount", event.ammount);
            row.addProperty("healthBefore", event.entityLiving.getHealth());
            row.addProperty("type", event.source.getDamageType());
            row.addProperty("explosion", event.source.isExplosion());
            row.addProperty("canceled", event.isCanceled());
            damage.add(row);
        }

        @Override
        JsonArray layout() {
            var rows = new JsonArray();
            for (int a = 0; a < 33; a++) for (int b = 0; b < 12; b++) for (int c = 0; c < 33; c++) {
                var block = helper.getWorld()
                    .getBlock(x + a, y + b, z + c);
                if (block == Blocks.air) continue;
                var row = new JsonObject();
                row.addProperty("x", a);
                row.addProperty("y", b);
                row.addProperty("z", c);
                row.addProperty("registry", net.minecraft.block.Block.blockRegistry.getNameForObject(block));
                row.addProperty(
                    "metadata",
                    helper.getWorld()
                        .getBlockMetadata(x + a, y + b, z + c));
                rows.add(row);
            }
            return rows;
        }

        JsonArray goals() {
            var rows = new JsonArray();
            int owned = 0;
            for (var entry : creeper.tasks.taskEntries) {
                var row = new JsonObject();
                row.addProperty(
                    "class",
                    entry.action.getClass()
                        .getName());
                row.addProperty("priority", entry.priority);
                if (entry.action instanceof com.tianluoqaq.tlmnh.common.ai.MaidCreeperAvoidance.AvoidMaidGoal) {
                    owned++;
                    helper.assertTrue(entry.priority == 1, "Source avoidance priority changed");
                }
                rows.add(row);
            }
            helper.assertTrue(owned == 1, "Native Creeper join did not install exactly one source avoidance goal");
            return rows;
        }

        @Override
        void close() {
            if (closed) return;
            try {
                if (creeper != null) creeper.setDead();
                for (int a = 0; a < 33; a++)
                    for (int b = 0; b < 12; b++) for (int c = 0; c < 33; c++) helper.setBlock(a, b, c, Blocks.air);
                if (oldFire != null) helper.getWorld()
                    .getGameRules()
                    .setOrCreateGameRule("doFireTick", oldFire);
                if (oldGrief != null) helper.getWorld()
                    .getGameRules()
                    .setOrCreateGameRule("mobGriefing", oldGrief);
            } finally {
                super.close();
            }
        }
    }

    public static final class PantryCollector extends ActivityCompetitionPortTests.Collector {

        int consumed, externalMouths, lastBites, epoch, placedAt = -1;

        PantryCollector(GameTestHelper h, MaidBrainProfile profile, String mechanism) {
            super(h, profile, mechanism, RESOURCE, CONTRACT, "terrain-life-port-v1", "terrain-life-");
        }

        @Override
        void setup() {
            super.setup();
            enclose(helper, 25, 8);
            identity(output);
        }

        @Override
        JsonArray layout() {
            var rows = super.layout();
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (row.get("registry")
                    .getAsString()
                    .equals("minecraft:glass")) row.addProperty("semantic", "glass");
            }
            return rows;
        }

        @Override
        void beforeSpawn() {
            super.beforeSpawn();
            var p = scene.getAsJsonArray("primarySpawn");
            var q = scene.getAsJsonArray("secondarySpawn");
            maid.setPosition(
                x + p.get(0)
                    .getAsDouble(),
                y + 1,
                z + p.get(2)
                    .getAsDouble());
            second.setPosition(
                x + q.get(0)
                    .getAsDouble(),
                y + 1,
                z + q.get(2)
                    .getAsDouble());
            maid.setHunger(0);
            if (scene.get("homeEnabled")
                .getAsBoolean()) {
                helper.assertTrue(
                    maid.getHomeArea()
                        .setEnabled(true)
                        && second.getHomeArea()
                            .setEnabled(true),
                    "Native pantry home rejected");
            }
            if (scene.get("backpackCake")
                .getAsInt() > 0)
                maid.getMaidInventory()
                    .setInventorySlotContents(0, new ItemStack(Items.cake));
            output.addProperty(
                "initialNonEmptyItemStackCount",
                scene.get("backpackCake")
                    .getAsInt());
            output.add("secondInitial", raw(second));
            output.add("secondAttributeInputs", SleepAttributePortSnapshot.capture(second));
            output.addProperty(
                "pantryScope",
                "natural roots/cooldowns; original2.5 approach contrasted with near entry, no RNG reset or direct task calls");
        }

        @Override
        void afterFrame(int index) {
            super.afterFrame(index);
            var row = frames.get(index)
                .getAsJsonObject();
            row.add("maidLifeTargets", lifeTargets(maid));
            row.add("secondLifeTargets", lifeTargets(second));
            if (index + 1 == frameLimit()) {
                output.addProperty("naturalPlacementFrame", placedAt);
                output.addProperty(
                    "behaviorSuccess",
                    triggerAt >= 0 && releaseAt >= 0 && (mechanism.equals("pantry_approach") || placedAt >= 0));
                output.addProperty(
                    "resourceAssertionScope",
                    "two finite cakes: consumed mouths + current world mouths + external withdrawn mouths + backpack cakes*7 ==14 for near; original one-cake guard for approach");
            }
        }

        JsonObject lifeTargets(EntityMaid actor) {
            var row = new JsonObject();
            var core = actor.getBrainCoordinator();
            var a = profile == MaidBrainProfile.ENHANCED ? core.peekEnhancedCore()
                .actor()
                : core.peekFaithfulBrain()
                    .getActor();
            var target = a.workTarget();
            if (target != null) row.add("workTarget", vector(target.x - x, target.y - y, target.z - z));
            row.addProperty("edibleAction", String.valueOf(a.edibleAction()));
            row.addProperty("walkMatchesWorkTarget", a.walkMatchesWorkTarget());
            row.addProperty("cakeBites", a.cakeBites());
            row.addProperty("cakePlacements", a.cakePlacements());
            row.addProperty("backpackCake", count(actor, Items.cake));
            return row;
        }

        @Override
        void cakeFrame(int index, JsonObject row) {
            if (mechanism.equals("pantry_approach")) {
                super.cakeFrame(index, row);
                return;
            }
            int backpack = count(maid, Items.cake) + count(second, Items.cake);
            boolean present = helper.getWorld()
                .getBlock(x + 12, y + 2, z + 12) == Blocks.cake;
            int bites = present ? cakeBites() : 7;
            if (epoch == 1 && present) {
                helper.assertTrue(backpack == 0, "Unowned new cake appeared while backpack original remains");
                epoch = 2;
                lastBites = 0;
                placedAt = index;
                event(index, "natural_backpack_cake_placement");
            }
            if (epoch != 1) {
                int delta = bites - lastBites;
                helper.assertTrue(
                    delta >= 0 && delta <= 2,
                    "Native shared mouth delta changed outside two natural actors");
                consumed += delta;
                lastBites = bites;
            }
            int remaining = present ? 7 - bites : 0;
            helper.assertTrue(
                consumed + externalMouths + remaining + backpack * 7 == 14,
                "Finite pantry cake mouths lost conservation");
            row.addProperty("cakeBitesConsumed", consumed);
            row.addProperty("externalWithdrawnMouths", externalMouths);
            row.addProperty("backpackCake", backpack);
            row.addProperty("worldCakeRemainingMouths", remaining);
            if (triggerAt < 0 && consumed > 0) {
                triggerAt = index;
                event(index, "first_shared_cake_bite_wait");
                maid.setSitting(true);
                second.setSitting(true);
            } else if (triggerAt >= 0 && releaseAt < 0 && index == triggerAt + 40) {
                releaseAt = index;
                event(index, "release_wait_and_withdraw_remaining_first_cake");
                maid.setSitting(false);
                second.setSitting(false);
                externalMouths = remaining;
                helper.getWorld()
                    .setBlockToAir(x + 12, y + 2, z + 12);
                epoch = 1;
                lastBites = 0;
            }
        }
    }
}
