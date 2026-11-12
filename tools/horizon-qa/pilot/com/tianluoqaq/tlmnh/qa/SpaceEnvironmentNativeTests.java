package com.tianluoqaq.tlmnh.qa;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.HorizonQAProperties;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 在已注册外星世界中观察正常实体 tick 的供氧、窒息事件及温度传播 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "GalacticraftCore", "GalaxySpace" })
public final class SpaceEnvironmentNativeTests {

    private static final String MERCURY = "galaxyspace.SolarSystem.planets.mercury.dimension.WorldProviderMercury";
    private static final String VENUS = "galaxyspace.SolarSystem.planets.venus.dimension.WorldProviderVenus";
    private static final String SPACE_BASE = "galaxyspace.core.dimension.WorldProviderSpaceGS";
    private static final String ATMOSPHERE_API = "micdoodle8.mods.galacticraft.api.world.IGalacticraftWorldProvider";
    private static final String MASK = "micdoodle8.mods.galacticraft.core.items.ItemOxygenMask";
    private static final String GEAR = "micdoodle8.mods.galacticraft.core.items.ItemOxygenGear";
    private static final String TANK = "micdoodle8.mods.galacticraft.core.items.ItemOxygenTank";
    private static final String LINING = "galaxyspace.core.item.ItemThermalPaddingTier2";
    private static final String OXYGEN_EVENT = "micdoodle8.mods.galacticraft.api.event.oxygen.GCCoreOxygenSuffocationEvent";
    private static final int FLOOR_Y = 240;
    private static final Map<GameRules, int[]> RULE_LEASES = new IdentityHashMap<>();
    private static int animalLeases;
    private static boolean previousAnimals;

    private SpaceEnvironmentNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "space_environment")
    public static void realPlanetOxygenEquipmentAndSuffocationFollowNaturalEntityTicks(GameTestHelper h) {
        var fixture = new PlanetFixture(h, "oxygen");
        var supplied = fixture.spawn("complete", 4, true, false, true);
        var missingMask = fixture.spawn("missing-mask", 8, false, false, true);
        var depleted = fixture.spawn("depleted-tank", 12, true, true, true);
        h.onEachTick("real planet oxygen and suffocation", () -> {
            fixture.observe();
            if (supplied.maid.ticksExisted < 20) return;
            h.assertTrue(
                supplied.tank.getItemDamage() > supplied.initialTankDamage,
                "Natural planet ticks consumed no oxygen");
            h.assertTrue(
                supplied.maid.getHealth() == supplied.initialHealth && supplied.pre == 0 && supplied.post == 0,
                "Complete live oxygen equipment did not protect the maid");
            assertSuffocation(h, missingMask);
            assertSuffocation(h, depleted);
            h.assertTrue(
                depleted.initialTankDamage == depleted.tank.getMaxDamage() - 1
                    && depleted.tank.getItemDamage() == depleted.tank.getMaxDamage(),
                "Real final oxygen unit was not exhausted");
            fixture.assertionsCompleted = true;
            fixture.save();
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "space_environment")
    public static void realPlanetFourThermalPartsPreventNaturalEnvironmentalHeatDamage(GameTestHelper h) {
        var fixture = new PlanetFixture(h, "thermal");
        var insulated = fixture.spawn("four-parts", 4, true, false, true);
        var exposed = fixture.spawn("no-lining", 12, true, false, false);
        var missingMask = fixture.spawn("missing-mask-long", 8, false, false, true, 200);
        var provider = CompatServices.spaceSurvival();
        int cooldown = Math.max(1, provider.suffocationCooldown());
        int baseDamage = Math.max(1, provider.suffocationDamageBase());
        boolean hardMode = provider.hardModeSuffocation();
        missingMask.expectedCooldown = cooldown;
        missingMask.expectedBase = baseDamage;
        missingMask.expectedHardMode = hardMode;
        int spacing = nativeDrainSpacing(insulated.tank);
        fixture.report.addProperty("longProbeInitialHealth", missingMask.initialHealth);
        fixture.report.addProperty("longProbeMaxHealth", missingMask.maid.getMaxHealth());
        fixture.report.addProperty("actualSuffocationCooldown", cooldown);
        fixture.report.addProperty("actualSuffocationDamageBase", baseDamage);
        fixture.report.addProperty("actualHardModeSuffocation", hardMode);
        fixture.report.addProperty("nativeTankDrainSpacing", spacing);
        h.onEachTick("real planet thermal protection", () -> {
            fixture.observe();
            if (exposed.maid.ticksExisted < 220) return;
            h.assertTrue(
                insulated.maid.getHealth() == insulated.initialHealth && insulated.thermalHurts == 0
                    && Math.abs(
                        insulated.maid.getSpaceSurvival()
                            .getThermalLevel())
                        <= 1,
                "Four real thermal parts failed to stabilize the actual planet temperature");
            h.assertTrue(
                exposed.maid.getSpaceSurvival()
                    .getThermalLevel() == 22 && exposed.thermalHurts > 0
                    && exposed.thermalAmount > 0
                    && exposed.maid.getHealth() < exposed.initialHealth,
                "Uninsulated maid did not suffer the real planet thermal source");
            h.assertTrue(
                insulated.uncanceledPre == 0 && exposed.uncanceledPre == 0
                    && insulated.post == 0
                    && exposed.post == 0
                    && insulated.oxygenHurts == 0
                    && exposed.oxygenHurts == 0,
                "Oxygen failure contaminated the paired thermal observation");
            h.assertTrue(
                insulated.tank.getItemDamage() > insulated.initialTankDamage
                    && exposed.tank.getItemDamage() > exposed.initialTankDamage,
                "Thermal actors did not consume actual oxygen");
            int nativeMobAttempts = exposed.maid.ticksExisted / 100;
            h.assertTrue(
                insulated.canceledPre == nativeMobAttempts && exposed.canceledPre == nativeMobAttempts
                    && missingMask.canceledPre == nativeMobAttempts,
                "Native 100/200-tick mob checks were not retained as canceled duplicate Pre events");
            int expectedHurts = 1 + (missingMask.maid.ticksExisted - 1) / cooldown;
            float expectedAmount = 0;
            for (int hit = 0; hit < expectedHurts; hit++)
                expectedAmount += baseDamage * (2 + (hardMode ? hit : 0)) / 2.0F;
            fixture.report.addProperty("longProbeExpectedHurtCount", expectedHurts);
            fixture.report.addProperty("longProbeExpectedRawDamage", expectedAmount);
            fixture.report.addProperty("longProbeDamageOnlyExpectedHealth", missingMask.initialHealth - expectedAmount);
            fixture.report.addProperty("longProbeAcceptedNativeHealing", missingMask.acceptedHealing);
            fixture.report.addProperty(
                "longProbeExpectedHealth",
                missingMask.initialHealth - expectedAmount + missingMask.acceptedHealing);
            int expectedDrain = 1 + (missingMask.maid.ticksExisted - 1) / spacing;
            fixture.report.addProperty("longProbeExpectedTankDrain", expectedDrain);
            h.assertTrue(
                missingMask.oxygenHurts == expectedHurts && missingMask.post == expectedHurts
                    && missingMask.oxygenAmount == expectedAmount
                    && missingMask.damageSequenceMatches
                    && missingMask.maid.getHealth()
                        == missingMask.initialHealth - expectedAmount + missingMask.acceptedHealing
                    && missingMask.thermalHurts == 0,
                "Missing-mask long probe changed actual suffocation cadence/damage or suffered duplicate mob damage");
            h.assertTrue(
                insulated.tank.getItemDamage() - insulated.initialTankDamage == expectedDrain
                    && exposed.tank.getItemDamage() - exposed.initialTankDamage == expectedDrain
                    && missingMask.tank.getItemDamage() - missingMask.initialTankDamage == expectedDrain,
                "Native oxygen drain differs or was charged twice");
            for (int part = 0; part < 4; part++) {
                var actual = insulated.maid.getMaidBaubleInventory()
                    .getStackInSlot(3 + part);
                h.assertTrue(
                    actual != null && actual.getItem()
                        .getClass()
                        .getName()
                        .equals(LINING) && actual.getItemDamage() == part,
                    "A real thermal part left its unlocked bauble slot");
            }
            fixture.assertionsCompleted = true;
            fixture.save();
            h.succeed();
        });
    }

    private static int nativeDrainSpacing(ItemStack tank) {
        try {
            var oxygen = Class.forName("micdoodle8.mods.galacticraft.core.util.OxygenUtil");
            int spacing = (int) MethodHandles.publicLookup()
                .findStatic(
                    oxygen,
                    "getDrainSpacing",
                    MethodType.methodType(int.class, ItemStack.class, ItemStack.class))
                .invoke(tank, null);
            if (spacing <= 0) throw new IllegalStateException("Native oxygen tank spacing must be positive");
            return spacing;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot bind native oxygen tank spacing", failure);
        }
    }

    private static void assertSuffocation(GameTestHelper h, Actor actor) {
        h.assertTrue(
            actor.pre > 0 && actor.post > 0
                && actor.oxygenHurts > 0
                && actor.oxygenAmount > 0
                && actor.maid.getHealth() < actor.initialHealth,
            "Missing/empty equipment caused no real suffocation events and damage: " + actor.label);
        h.assertTrue(actor.thermalHurts == 0, "Thermal damage contaminated short oxygen observation: " + actor.label);
    }

    private static ItemStack item(String className) {
        for (Object value : Item.itemRegistry) if (value instanceof Item item && item.getClass()
            .getName()
            .equals(className)) return new ItemStack(item);
        throw new IllegalStateException("Required registered planet equipment absent: " + className);
    }

    private static WorldServer planet(GameTestHelper h, String expectedProvider) {
        for (int id : DimensionManager.getStaticDimensionIDs()) {
            var provider = DimensionManager.createProviderFor(id);
            if (!provider.getClass()
                .getName()
                .equals(expectedProvider)) continue;
            if (DimensionManager.getWorld(id) == null) DimensionManager.initDimension(id);
            var world = DimensionManager.getWorld(id);
            h.assertTrue(
                world != null && world.provider.dimensionId == id
                    && world.provider.getClass()
                        .getName()
                        .equals(expectedProvider),
                "Registered planet world/provider binding differs");
            return world;
        }
        throw new IllegalStateException(
            "The actual GalaxySpace provider has no registered dimension: " + expectedProvider);
    }

    private static void acquireRules(WorldServer world) {
        var rules = world.getGameRules();
        var lease = RULE_LEASES.get(rules);
        if (lease == null) {
            lease = new int[] { 0, world.getGameRules()
                .getGameRuleBooleanValue("naturalRegeneration") ? 1 : 0 };
            RULE_LEASES.put(rules, lease);
        }
        lease[0]++;
        world.getGameRules()
            .setOrCreateGameRule("naturalRegeneration", "false");
        var server = MinecraftServer.getServer();
        if (animalLeases++ == 0) previousAnimals = server.getCanSpawnAnimals();
        server.setCanSpawnAnimals(true);
    }

    private static void releaseRules(WorldServer world) {
        var rules = world.getGameRules();
        var lease = RULE_LEASES.get(rules);
        if (--lease[0] == 0) {
            world.getGameRules()
                .setOrCreateGameRule("naturalRegeneration", Boolean.toString(lease[1] != 0));
            RULE_LEASES.remove(rules);
        }
        if (--animalLeases == 0) MinecraftServer.getServer()
            .setCanSpawnAnimals(previousAnimals);
    }

    private static final class Actor {

        final String label;
        final EntityMaid maid;
        final ItemStack tank;
        final int initialTankDamage;
        final float initialHealth;
        int updates;
        int pre;
        int canceledPre;
        int uncanceledPre;
        int post;
        int oxygenHurts;
        int thermalHurts;
        float oxygenAmount;
        float thermalAmount;
        float acceptedHealing;
        int healEvents;
        int expectedCooldown;
        int expectedBase;
        boolean expectedHardMode;
        boolean damageSequenceMatches = true;
        int previousAge;
        boolean observed;

        Actor(String label, EntityMaid maid, ItemStack tank) {
            this.label = label;
            this.maid = maid;
            this.tank = tank;
            initialTankDamage = tank.getItemDamage();
            initialHealth = maid.getHealth();
        }
    }

    public static final class PlanetFixture {

        final GameTestHelper h;
        final String scenario;
        final WorldServer world;
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final List<Actor> actors = new ArrayList<>();
        final List<int[]> floor = new ArrayList<>();
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray();
        final JsonArray events = new JsonArray();
        final DamageSource oxygen;
        final DamageSource thermal;
        final int x;
        final int z;
        boolean rulesAcquired;
        boolean assertionsCompleted;
        boolean closed;
        int nativeWorldEndTicks;
        int previousWorldEndTicks;
        boolean worldObserved;

        PlanetFixture(GameTestHelper h, String scenario) {
            this.h = h;
            this.scenario = scenario;
            boolean thermalScenario = scenario.equals("thermal");
            String expectedProvider = thermalScenario ? VENUS : MERCURY;
            float expectedModifier = thermalScenario ? 5.0F : 3.0F;
            String expectedDeclaringClass = thermalScenario ? VENUS : SPACE_BASE;
            world = planet(h, expectedProvider);
            oxygen = nativeDamage("oxygenSuffocation");
            thermal = nativeDamage("thermal");
            x = (8192 + h.getOriginX() * 4) & ~15;
            z = (8192 + h.getOriginZ() * 4) & ~15;
            h.afterTest(this::close);
            report.addProperty("schema", "real-planet-survival-natural-tick-v3");
            report.addProperty("scenario", scenario);
            report.addProperty("dimension", world.provider.dimensionId);
            report.addProperty(
                "provider",
                world.provider.getClass()
                    .getName());
            report.addProperty("worldSeed", world.getSeed());
            report.addProperty(
                "worldClass",
                world.getClass()
                    .getName());
            report.addProperty(
                "gameRuleBeforeLease",
                world.getGameRules()
                    .getGameRuleBooleanValue("naturalRegeneration"));
            report.addProperty("gameRulesIdentity", System.identityHashCode(world.getGameRules()));
            report.addProperty("hqaMode", HorizonQAProperties.modeName());
            report.addProperty(
                "effectiveAllowOtherDimensions",
                MinecraftServer.getServer()
                    .getAllowNether());
            report.addProperty("fakePlayersCreated", 0);
            report.addProperty("manualEntityOrControllerTicks", 0);
            report.add("frames", frames);
            report.add("events", events);
            save();
            h.assertTrue(
                HorizonQAProperties.isInteractive() && HorizonQAProperties.autoRunTests()
                    && HorizonQAProperties.stopServerAfterRun()
                    && HorizonQAProperties.turboMultiplier() == 1
                    && MinecraftServer.getServer()
                        .getAllowNether(),
                "Native non-headless dimension ticking policy required");
            acquireRules(world);
            rulesAcquired = true;
            var provider = CompatServices.spaceSurvival();
            h.assertTrue(provider.isAvailable(), "Real GC survival integration unavailable");
            var actual = provider.sampleAtmosphere(world);
            report.addProperty("galacticraftDimension", actual.galacticraftDimension);
            report.addProperty("breathableAtmosphere", actual.breathableAtmosphere);
            report.addProperty("thermalLevelModifier", actual.thermalLevelModifier);
            rawAtmosphere(expectedModifier, expectedDeclaringClass, actual);
            try {
                chunks.forceChunksStrict(world, x - 32, 0, z - 32, x + 47, 255, z + 47);
            } catch (Exception failure) {
                throw new IllegalStateException("Real planet chunk lease failed", failure);
            }
            for (int dx = 2; dx <= 14; dx++) for (int dz = 5; dz <= 11; dz++) {
                for (int dy = 0; dy <= 4; dy++) h.assertTrue(
                    world.isAirBlock(x + dx, FLOOR_Y + dy, z + dz),
                    "Planet fixture refuses an occupied block");
            }
            for (int dx = 2; dx <= 14; dx++) for (int dz = 5; dz <= 11; dz++) {
                h.assertTrue(
                    world.setBlock(x + dx, FLOOR_Y, z + dz, net.minecraft.init.Blocks.bedrock, 0, 2),
                    "Planet safety platform was rejected");
                floor.add(new int[] { x + dx, FLOOR_Y, z + dz });
            }
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            save();
        }

        Actor spawn(String label, int dx, boolean mask, boolean finalUnit, boolean lining) {
            return spawn(label, dx, mask, finalUnit, lining, 20);
        }

        Actor spawn(String label, int dx, boolean mask, boolean finalUnit, boolean lining, float initialHealth) {
            var maid = new EntityMaid(world);
            h.assertTrue(maid.bindTo(UUID.randomUUID()), "Planet maid initial binding failed");
            maid.setSitting(true);
            maid.func_110163_bv();
            maid.getHomeArea()
                .setEnabled(false);
            maid.setPosition(x + dx + .5, FLOOR_Y + 1, z + 8.5);
            maid.setHunger(20);
            var baubles = maid.getMaidBaubleInventory();
            if (mask) baubles.setInventorySlotContents(0, item(MASK));
            baubles.setInventorySlotContents(1, item(GEAR));
            var tank = item(TANK);
            tank.setItemDamage(finalUnit ? tank.getMaxDamage() - 1 : 0);
            baubles.setInventorySlotContents(2, tank);
            if (lining) for (int part = 0; part < 4; part++) {
                var padding = item(LINING);
                padding.setItemDamage(part);
                baubles.setInventorySlotContents(3 + part, padding);
            }
            maid.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(initialHealth);
            maid.setHealth(initialHealth);
            var actor = new Actor(label, maid, baubles.getStackInSlot(2));
            actors.add(actor);
            h.assertTrue(
                !CompatServices.spaceSurvival()
                    .isInBreathableAir(maid, false)
                    && !CompatServices.spaceSurvival()
                        .isInBreathableAir(maid, true),
                "Actual planet fixture contains oxygen or temperature-normalized air");
            h.assertTrue(
                world.spawnEntityInWorld(maid) && world.getEntityByID(maid.getEntityId()) == maid,
                "Actual planet world rejected the owned maid");
            return actor;
        }

        private void rawAtmosphere(float expectedModifier, String expectedDeclaringClass,
            com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider.AtmosphereSample sample) {
            try {
                Class<?> contract = Class.forName(ATMOSPHERE_API, false, getClass().getClassLoader());
                var lookup = MethodHandles.publicLookup();
                var signature = MethodType.methodType(float.class);
                var concrete = lookup.findVirtual(world.provider.getClass(), "getThermalLevelModifier", signature);
                String declaring = lookup.revealDirect(concrete)
                    .getDeclaringClass()
                    .getName();
                float raw = (float) lookup.findVirtual(contract, "getThermalLevelModifier", signature)
                    .invoke(world.provider);
                float rawConcrete = (float) concrete.invoke(world.provider);
                boolean breathable = (boolean) lookup
                    .findVirtual(contract, "hasBreathableAtmosphere", MethodType.methodType(boolean.class))
                    .invoke(world.provider);
                report.addProperty("runtimeThermalGetterDeclaringClass", declaring);
                report.addProperty("expectedThermalGetterDeclaringClass", expectedDeclaringClass);
                report.addProperty("rawInterfaceThermalModifier", raw);
                report.addProperty("rawConcreteThermalModifier", rawConcrete);
                report.addProperty("rawInterfaceBreathable", breathable);
                report.addProperty("domainSampleThermalModifier", sample.thermalLevelModifier);
                report.addProperty("domainNormalizationApplied", false);
                report.add(
                    "providerArtifact",
                    DevelopmentArtifactIdentity.capture(
                        world.provider.getClass(),
                        world.provider.getClass()
                            .getSuperclass()));
                save();
                h.assertTrue(
                    contract.isInstance(world.provider) && declaring.equals(expectedDeclaringClass)
                        && raw == expectedModifier
                        && rawConcrete == raw
                        && sample.thermalLevelModifier == raw
                        && !breathable
                        && sample.breathableAtmosphere == breathable
                        && sample.galacticraftDimension,
                    "Actual provider inheritance/raw atmosphere/domain sample binding differs");
            } catch (Throwable failure) {
                if (failure instanceof Error error) throw error;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Cannot bind actual planet atmosphere API", failure);
            }
        }

        void observe() {
            var row = new JsonObject();
            row.addProperty("worldTime", world.getTotalWorldTime());
            row.addProperty("nativeWorldEndTicks", nativeWorldEndTicks);
            row.addProperty("gameRulesIdentity", System.identityHashCode(world.getGameRules()));
            row.addProperty(
                "naturalRegeneration",
                world.getGameRules()
                    .getGameRuleBooleanValue("naturalRegeneration"));
            row.addProperty("registeredWorldSame", DimensionManager.getWorld(world.provider.dimensionId) == world);
            row.addProperty(
                "effectiveAllowOtherDimensions",
                MinecraftServer.getServer()
                    .getAllowNether());
            var attempts = new JsonArray();
            for (var actor : actors) {
                var attempt = new JsonObject();
                attempt.addProperty("label", actor.label);
                attempt.addProperty("age", actor.maid.ticksExisted);
                attempt.addProperty("livingUpdates", actor.updates);
                attempts.add(attempt);
            }
            row.add("attemptedActorTicks", attempts);
            report.add("lastObservationAttempt", row);
            save();
            h.assertTrue(
                DimensionManager.getWorld(world.provider.dimensionId) == world && !world.getGameRules()
                    .getGameRuleBooleanValue("naturalRegeneration"),
                "Planet world/rule binding changed");
            if (worldObserved) h.assertTrue(
                nativeWorldEndTicks == previousWorldEndTicks + 1,
                "Actual planet did not receive exactly one normal WorldTick.END per server tick");
            previousWorldEndTicks = nativeWorldEndTicks;
            worldObserved = true;
            var samples = new JsonArray();
            for (var actor : actors) {
                var maid = actor.maid;
                h.assertTrue(
                    maid.isEntityAlive() && maid.worldObj == world
                        && world.getEntityByID(maid.getEntityId()) == maid
                        && maid.dimension == world.provider.dimensionId,
                    "Owned planet actor left its real world or died: " + actor.label);
                h.assertTrue(
                    actor.updates == maid.ticksExisted,
                    "Planet actor did not have exactly one natural living update per age");
                if (actor.observed) h.assertTrue(
                    maid.ticksExisted == actor.previousAge + 1,
                    "Forced planet chunks stopped natural entity ticks");
                actor.observed = true;
                actor.previousAge = maid.ticksExisted;
                var sample = new JsonObject();
                sample.addProperty("label", actor.label);
                sample.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                sample.addProperty("age", maid.ticksExisted);
                sample.addProperty("naturalLivingUpdates", actor.updates);
                sample.addProperty("health", maid.getHealth());
                sample.addProperty("initialHealth", actor.initialHealth);
                sample.addProperty("maxHealth", maid.getMaxHealth());
                sample.addProperty("healEvents", actor.healEvents);
                sample.addProperty("acceptedNativeHealing", actor.acceptedHealing);
                sample.addProperty("damageSequenceMatches", actor.damageSequenceMatches);
                float expectedHealth = actor.initialHealth - actor.oxygenAmount
                    - actor.thermalAmount
                    + actor.acceptedHealing;
                sample.addProperty("eventLedgerExpectedHealth", expectedHealth);
                h.assertTrue(
                    maid.getHealth() == expectedHealth,
                    "Actual planet health differs from accepted native damage/heal event order: " + actor.label);
                sample.addProperty("oxygenTankDamage", actor.tank.getItemDamage());
                sample.addProperty("oxygenTankMaxDamage", actor.tank.getMaxDamage());
                sample.addProperty(
                    "thermalLevel",
                    maid.getSpaceSurvival()
                        .getThermalLevel());
                sample.addProperty("suffocationPre", actor.pre);
                sample.addProperty("canceledSuffocationPre", actor.canceledPre);
                sample.addProperty("uncanceledSuffocationPre", actor.uncanceledPre);
                sample.addProperty("suffocationPost", actor.post);
                sample.addProperty("oxygenHurtEvents", actor.oxygenHurts);
                sample.addProperty("thermalHurtEvents", actor.thermalHurts);
                samples.add(sample);
            }
            row.add("actors", samples);
            frames.add(row);
            save();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onLiving(LivingEvent event) {
            for (var actor : actors) {
                if (event.entityLiving != actor.maid) continue;
                if (event instanceof LivingEvent.LivingUpdateEvent) {
                    actor.updates++;
                    return;
                }
                String name = event.getClass()
                    .getName();
                if (name.equals(OXYGEN_EVENT + "$Pre")) {
                    actor.pre++;
                    if (event.isCanceled()) actor.canceledPre++;
                    else actor.uncanceledPre++;
                } else if (name.equals(OXYGEN_EVENT + "$Post")) actor.post++;
                else if (event instanceof LivingHurtEvent hurt) {
                    if (!hurt.isCanceled() && hurt.source == oxygen) {
                        if (actor.expectedCooldown > 0) {
                            int index = actor.oxygenHurts;
                            actor.damageSequenceMatches &= actor.maid.ticksExisted == 1 + index * actor.expectedCooldown
                                && hurt.ammount
                                    == actor.expectedBase * (2 + (actor.expectedHardMode ? index : 0)) / 2.0F;
                        }
                        actor.oxygenHurts++;
                        actor.oxygenAmount += hurt.ammount;
                    }
                    if (!hurt.isCanceled() && hurt.source == thermal) {
                        actor.thermalHurts++;
                        actor.thermalAmount += hurt.ammount;
                    }
                } else if (event instanceof LivingHealEvent heal) {
                    actor.healEvents++;
                    if (!heal.isCanceled() && heal.amount > 0 && actor.maid.getHealth() > 0)
                        actor.acceptedHealing += Math
                            .min(heal.amount, actor.maid.getMaxHealth() - actor.maid.getHealth());
                } else return;
                var record = new JsonObject();
                record.addProperty("eventOrder", events.size());
                record.addProperty("actor", actor.label);
                record.addProperty("age", actor.maid.ticksExisted);
                record.addProperty("event", name);
                record.addProperty("canceled", event.isCancelable() && event.isCanceled());
                record.addProperty("dimension", actor.maid.dimension);
                record.addProperty(
                    "provider",
                    actor.maid.worldObj.provider.getClass()
                        .getName());
                if (event instanceof LivingHurtEvent hurt) {
                    record.addProperty("source", hurt.source.getDamageType());
                    record.addProperty("amount", hurt.ammount);
                    record.addProperty("healthBefore", actor.maid.getHealth());
                }
                if (event instanceof LivingHealEvent heal) {
                    record.addProperty("amount", heal.amount);
                    record.addProperty("healthBefore", actor.maid.getHealth());
                    record.addProperty("maxHealth", actor.maid.getMaxHealth());
                    float accepted = !heal.isCanceled() && heal.amount > 0 && actor.maid.getHealth() > 0
                        ? Math.min(heal.amount, actor.maid.getMaxHealth() - actor.maid.getHealth())
                        : 0;
                    record.addProperty("acceptedAmountAtObservation", accepted);
                    record.addProperty("verificationBoundary", "after native tick exact event-ledger health equality");
                    var origin = new JsonArray();
                    for (var frame : Thread.currentThread()
                        .getStackTrace())
                        if (frame.getClassName()
                            .startsWith("com.tianluoqaq.tlmnh.common.")
                            || frame.getClassName()
                                .startsWith("com.github.tartaricacid.touhoulittlemaid."))
                            origin.add(
                                new com.google.gson.JsonPrimitive(frame.getClassName() + "." + frame.getMethodName()));
                    record.add("sourceCallFrames", origin);
                }
                events.add(record);
            }
        }

        @SubscribeEvent
        public void onWorldTick(TickEvent.WorldTickEvent event) {
            if (event.world == world && event.phase == TickEvent.Phase.END) nativeWorldEndTicks++;
        }

        void save() {
            try {
                String directory = System.getProperty("horizonqa.reportDir");
                if (directory == null || directory.isEmpty())
                    throw new IllegalStateException("Explicit native report directory required");
                report.addProperty("assertionsCompleted", assertionsCompleted);
                var path = Paths.get(directory, "space-real-planet-" + scenario + ".json");
                Files.createDirectories(path.getParent());
                Files.write(
                    path,
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot preserve actual planet evidence", failure);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            MinecraftForge.EVENT_BUS.unregister(this);
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            try {
                for (var actor : actors) {
                    Entity actual = world.getEntityByID(actor.maid.getEntityId());
                    h.assertTrue(
                        actual == null || actual == actor.maid,
                        "Planet cleanup refused a foreign entity successor");
                    world.removePlayerEntityDangerously(actor.maid);
                }
                for (var at : floor) {
                    h.assertTrue(
                        world.getBlock(at[0], at[1], at[2]) == net.minecraft.init.Blocks.bedrock
                            && world.getBlockMetadata(at[0], at[1], at[2]) == 0,
                        "Planet cleanup refused an externally changed block");
                    world.setBlockToAir(at[0], at[1], at[2]);
                }
                report.addProperty("ownedCleanupCompleted", true);
            } finally {
                try {
                    if (rulesAcquired) releaseRules(world);
                } finally {
                    chunks.releaseAll();
                    report.addProperty("chunkLeaseReleased", true);
                    save();
                }
            }
        }
    }

    private static DamageSource nativeDamage(String field) {
        try {
            return (DamageSource) Class.forName("micdoodle8.mods.galacticraft.core.util.DamageSourceGC")
                .getField(field)
                .get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Real GC damage source unavailable", failure);
        }
    }
}
