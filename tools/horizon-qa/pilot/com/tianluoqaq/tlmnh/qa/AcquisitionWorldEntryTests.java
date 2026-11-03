package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/** 冷启动已注册条目到真实原生Spawner；受控表隔离不冒充默认整合包密度/生成概率 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class AcquisitionWorldEntryTests {

    private AcquisitionWorldEntryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "acquisition_missing_worldgen_hook")
    public static void unavailableWorldgenHookDisablesOnlyNaturalRegistration(GameTestHelper helper) {
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaNaturalWorldgenHookDisabled")
                && MaidAcquisitionConfig.naturalSpawning,
            "Explicit cold-start disabled-hook negative fixture required");
        var fault = IntegrationGuard.faults(Side.SERVER)
            .get("acquisition/natural_worldgen");
        helper.assertTrue(fault != null, "Missing worldgen hook did not produce its exact feature diagnosis");
        for (var biome : BiomeGenBase.getBiomeGenArray())
            if (biome != null) for (Object value : biome.getSpawnableList(EnumCreatureType.creature)) helper.assertTrue(
                ((BiomeGenBase.SpawnListEntry) value).entityClass != EntityMaid.class,
                "Missing hook left a native maid spawn registration enabled");
        var fixture = new ProgressionTests.Fixture(helper, false);
        boolean gift = MaidAcquisitionConfig.giveInitialSlab;
        try {
            MaidAcquisitionConfig.giveInitialSlab = true;
            helper.assertTrue(
                MaidLifecycleEvents.giveInitial(fixture.owner),
                "Natural hook fault incorrectly disabled the independent normal gift entry");
            var slab = fixture.owner.inventory.getCurrentItem();
            helper.assertTrue(
                slab != null && slab.getItem() == MaidResurrectionContent.INITIAL_SLAB,
                "The normal gift was not in the actual native selected slot");
            helper.assertTrue(
                MaidLifecycleService.releaseInitial(fixture.owner, slab, fixture.x + 5, fixture.y, fixture.z + 4)
                    == MaidLifecycleService.Result.SUCCESS,
                "Natural hook fault incorrectly disabled ordinary slab release");
            var maids = fixture.maids();
            helper.assertTrue(
                maids.size() == 1 && MaidSpawnChecks.present(maids.get(0))
                    && maids.get(0)
                        .isOwner(fixture.owner),
                "Existing/manual live entity entry was rejected");
            var released = maids.get(0);
            helper.afterTest(() -> {
                var world = helper.getWorld();
                helper.assertTrue(
                    released.worldObj == world && world.getEntityByID(released.getEntityId()) == released
                        && world.loadedEntityList.stream()
                            .anyMatch(value -> value == released),
                    "Unknown manual-entry cleanup successor retained");
                released.setDead();
                world.removePlayerEntityDangerously(released);
            });
            helper.assertTrue(
                IntegrationGuard.faults(Side.SERVER)
                    .get("acquisition/natural_worldgen") == fault,
                "Negative fixture erased or replaced the actual startup fault");
            System.out.println(
                "TLMNH_ACQUISITION_MISSING_WORLDGEN_PASS key=acquisition/natural_worldgen code=" + fault.code
                    + " registration=disabled gift=independent ordinarySlab=successful existingActor=retained");
        } finally {
            MaidAcquisitionConfig.giveInitialSlab = gift;
        }
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1200, batch = "acquisition_native_spawner")
    public static void startupRegistrationProducesAnUntamedMaidThroughNativeSpawner(GameTestHelper helper)
        throws Exception {
        helper.assertTrue(
            MaidAcquisitionConfig.naturalSpawning
                && MaidAcquisitionConfig.allowsDimension(helper.getWorld().provider.dimensionId),
            "Cold-start enabled natural-spawn config and allowed actual dimension required; no runtime registration fallback");
        var fixture = new ProgressionTests.Fixture(helper, false);
        var attempt = new NativeSpawnAttempt(helper, fixture, "native-spawn");
        attempt.prepare();
        helper.onEachTick("actual native SpawnerAnimals", attempt::tick);
        helper.succeedWhen(() -> attempt.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 600, batch = "acquisition_worldgen_gate")
    public static void nativeWorldgenHonorsEnabledAndDimensionWhileExistingMaidsRemain(GameTestHelper helper)
        throws Exception {
        var fixture = new ProgressionTests.Fixture(helper, false);
        var attempt = new NativeSpawnAttempt(helper, fixture, "worldgen-gate");
        attempt.prepare();
        helper.onEachTick("native worldgen after real lighting settles", () -> {
            if (attempt.complete || !attempt.readyForSpawning()) return;
            boolean originalNatural = MaidAcquisitionConfig.naturalSpawning;
            var originalDimensions = MaidAcquisitionConfig.naturalDimensions;
            try {
                helper.assertTrue(
                    originalNatural && MaidAcquisitionConfig.allowsDimension(attempt.world.provider.dimensionId),
                    "Actual startup registration and original allowed dimension required");
                long seed = 0;
                while (new Random(seed).nextFloat() >= attempt.biome.getSpawningChance()) {
                    helper.assertTrue(++seed < 100000, "No bounded native worldgen group seed found");
                }
                var arms = new JsonArray();
                for (int arm = 0; arm < 3; arm++) {
                    MaidAcquisitionConfig.naturalSpawning = arm != 1;
                    MaidAcquisitionConfig.naturalDimensions = arm == 2 ? new int[0] : originalDimensions;
                    int before = attempt.worldgenJoined.size();
                    attempt.worldgenCall = true;
                    try {
                        SpawnerAnimals.performWorldGenSpawning(
                            attempt.world,
                            attempt.biome,
                            attempt.platformX,
                            attempt.platformZ,
                            32,
                            32,
                            new Random(seed));
                    } finally {
                        attempt.worldgenCall = false;
                    }
                    int created = attempt.worldgenJoined.size() - before;
                    helper.assertTrue(
                        arm == 0 ? created > 0 : created == 0,
                        "Native worldgen bypassed its exact enabled/dimension submission gate: arm=" + arm);
                    var row = new JsonObject();
                    row.addProperty("arm", arm == 0 ? "allowed" : arm == 1 ? "disabled" : "dimension-denied");
                    row.addProperty("nativeRandomSeed", seed);
                    row.addProperty("registeredWeightUnchanged", attempt.registered.itemWeight);
                    row.addProperty("nativeJoins", created);
                    arms.add(row);
                }
                for (var maid : attempt.worldgenJoined) helper.assertTrue(
                    MaidSpawnChecks.present(maid) && !maid.isTamed(),
                    "Allowed worldgen source was removed merely because the later configuration denied new spawns");
                helper.assertTrue(
                    attempt.checked.isEmpty(),
                    "Worldgen unexpectedly fired CheckSpawn; source contract differs");
                attempt.report.add("worldgenArms", arms);
                attempt.complete = true;
                attempt.evidence("passed");
            } finally {
                MaidAcquisitionConfig.naturalSpawning = originalNatural;
                MaidAcquisitionConfig.naturalDimensions = originalDimensions;
            }
        });
        helper.succeedWhen(() -> attempt.complete);
    }

    public static final class NativeSpawnAttempt {

        final GameTestHelper helper;
        final ProgressionTests.Fixture fixture;
        final WorldServer world;
        final Map<List<BiomeGenBase.SpawnListEntry>, List<BiomeGenBase.SpawnListEntry>> tables = new IdentityHashMap<>();
        final Map<EntityMaid, Boolean> checked = new IdentityHashMap<>();
        final List<EntityMaid> joined = new ArrayList<>();
        final List<EntityMaid> worldgenJoined = new ArrayList<>();
        final List<SavedBlock> blocks = new ArrayList<>();
        final GameTestChunkLoader platformChunks = new GameTestChunkLoader();
        final Map<ChunkCoordIntPair, Chunk> retainedChunks = new java.util.LinkedHashMap<>();
        final SpawnerAnimals spawner = new SpawnerAnimals();
        final JsonObject report = new JsonObject();
        final long originalTime;
        final long started;
        final String mechanism;
        final String originalMobSpawning;
        final int platformX, platformZ;
        // Keep standing Y below native random-point sampling's exclusive top-section bound.
        final int platformY = 188;
        BiomeGenBase biome;
        BiomeGenBase.SpawnListEntry registered;
        int calls, lightingTicks, stableLightTicks;
        long lastLightingTick = Long.MIN_VALUE;
        boolean complete, nativeCall, worldgenCall;

        NativeSpawnAttempt(GameTestHelper helper, ProgressionTests.Fixture fixture, String mechanism) {
            this.helper = helper;
            this.fixture = fixture;
            this.mechanism = mechanism;
            platformX = fixture.x + 64;
            platformZ = fixture.z - 96;
            world = helper.getWorld();
            originalMobSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
            originalTime = world.getWorldTime();
            started = world.getTotalWorldTime();
            helper.afterTest(this::close);
        }

        @SuppressWarnings("unchecked")
        void prepare() throws Exception {
            helper.assertTrue(
                world.spawnEntityInWorld(fixture.owner) && MaidSpawnChecks.present(fixture.owner)
                    && world.playerEntities.contains(fixture.owner),
                "Native spawner requires its genuinely registered player");
            int x = platformX, z = platformZ;
            biome = world.getWorldChunkManager()
                .getBiomeGenAt(x, z);
            for (Object value : biome.getSpawnableList(EnumCreatureType.creature)) {
                var entry = (BiomeGenBase.SpawnListEntry) value;
                if (entry.entityClass != EntityMaid.class) continue;
                helper.assertTrue(registered == null, "Duplicate startup maid registration");
                registered = entry;
            }
            helper.assertTrue(
                registered != null && registered.itemWeight == MaidAcquisitionConfig.naturalWeight
                    && registered.minGroupCount == 1
                    && registered.maxGroupCount == 1,
                "Actual terrain biome lacks the exact cold-start registration/weight/one-maid group");
            report.addProperty("schema", "native-acquisition-spawn-v1");
            report.addProperty("mechanism", mechanism);
            report.addProperty("biome", biome.biomeID);
            report.addProperty("dimension", world.provider.dimensionId);
            report.addProperty("registeredWeight", registered.itemWeight);
            report.addProperty("registeredMinGroup", registered.minGroupCount);
            report.addProperty("registeredMaxGroup", registered.maxGroupCount);
            report.addProperty("configuredGift", MaidAcquisitionConfig.giveInitialSlab);
            report.addProperty("configuredNatural", MaidAcquisitionConfig.naturalSpawning);
            report.addProperty("originalMobSpawning", originalMobSpawning);
            report.addProperty(
                "worldType",
                world.getWorldInfo()
                    .getTerrainType()
                    .getWorldTypeName());
            report.addProperty(
                "chunkProvider",
                world.theChunkProviderServer.currentChunkProvider.getClass()
                    .getName());
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            report.addProperty(
                "scope",
                "Explicit native findChunksForSpawning call, startup entry and native CheckSpawn/join; controlled grass/day/player placement and temporary creature tables; not default ecology, world-scheduler cadence or balance");
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    MaidResurrectionContent.class,
                    MaidAcquisitionConfig.class,
                    MaidLifecycleEvents.class,
                    EntityMaid.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity
                    .capture(AcquisitionWorldEntryTests.class, NativeSpawnAttempt.class, ProgressionTests.class));
            for (var candidate : BiomeGenBase.getBiomeGenArray()) {
                if (candidate == null) continue;
                for (var type : EnumCreatureType.values()) {
                    List<BiomeGenBase.SpawnListEntry> table = candidate.getSpawnableList(type);
                    if (table == null) continue;
                    if (tables.containsKey(table)) continue;
                    tables.put(table, new ArrayList<>(table));
                    table.clear();
                }
            }
            // Native Spawner uses a 17x17 player neighborhood. Generate it with creature tables isolated,
            // then restore only the original maid entry in the actual terrain biome for this bounded case.
            int cx = net.minecraft.util.MathHelper.floor_double(fixture.owner.posX / 16);
            int cz = net.minecraft.util.MathHelper.floor_double(fixture.owner.posZ / 16);
            for (int dx = -8; dx <= 8; dx++)
                for (int dz = -8; dz <= 8; dz++) world.getChunkFromChunkCoords(cx + dx, cz + dz);
            biome.getSpawnableList(EnumCreatureType.creature)
                .add(registered);
            report.add("nativeCapBefore", capSnapshot());
            var existing = new JsonArray();
            for (Object value : world.loadedEntityList)
                if (value instanceof Entity entity && entity.isCreatureType(EnumCreatureType.creature, true)) {
                    var row = new JsonObject();
                    row.addProperty(
                        "uuid",
                        entity.getUniqueID()
                            .toString());
                    row.addProperty(
                        "class",
                        entity.getClass()
                            .getName());
                    row.addProperty("dead", entity.isDead);
                    existing.add(row);
                }
            report.add("existingNativeCreaturesRetained", existing);
            world.setWorldTime(6000);
            // A getChunk call only loads once; the far native-spawn platform is outside the test cell ticket.
            // Hold the real Forge tickets until exact actor cleanup and original block restoration have finished.
            // World.updateLightByType requires the real 17-block neighbor radius to exist as well.
            final int lightRadius = 17;
            platformChunks.forceChunksStrict(
                world,
                x - lightRadius,
                platformY,
                z - lightRadius,
                x + 31 + lightRadius,
                platformY + 3,
                z + 31 + lightRadius);
            for (int chunkX = (x - lightRadius) >> 4; chunkX <= (x + 31 + lightRadius) >> 4; chunkX++)
                for (int chunkZ = (z - lightRadius) >> 4; chunkZ <= (z + 31 + lightRadius) >> 4; chunkZ++) {
                    var chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
                    helper.assertTrue(chunk.isChunkLoaded, "Native platform ticket did not load its actual chunk");
                    retainedChunks.put(new ChunkCoordIntPair(chunkX, chunkZ), chunk);
                }
            int changedBlocks = 0, unchangedAir = 0;
            for (int dx = 0; dx < 32; dx++) for (int dz = 0; dz < 32; dz++) {
                for (int dy = 0; dy < 4; dy++) {
                    int bx = x + dx, by = platformY + dy, bz = z + dz;
                    helper.assertTrue(world.getTileEntity(bx, by, bz) == null, "Unknown tile on native spawn platform");
                    helper.assertTrue(
                        world.isAirBlock(bx, by, bz),
                        "Native sky platform must not replace another fixture or unknown block");
                    blocks.add(
                        new SavedBlock(bx, by, bz, world.getBlock(bx, by, bz), world.getBlockMetadata(bx, by, bz)));
                    var target = dy == 0 ? Blocks.grass : Blocks.air;
                    boolean changed = world.setBlock(bx, by, bz, target, 0, 2);
                    if (changed) changedBlocks++;
                    else if (target == Blocks.air) unchangedAir++;
                    helper.assertTrue(
                        (changed || target == Blocks.air) && world.getBlock(bx, by, bz) == target
                            && world.getBlockMetadata(bx, by, bz) == 0,
                        "Native platform setBlock/readback rejected at " + bx + "," + by + "," + bz);
                }
            }
            report.addProperty("setBlockChanged", changedBlocks);
            report.addProperty("setBlockAlreadyAir", unchangedAir);
            report.addProperty("platformChunkRetention", "HorizonQA GameTestChunkLoader real Forge NORMAL ticket");
            report.addProperty("nativeLightNeighborRadius", lightRadius);
            report.add("platformChunksAfterPlacement", chunkSnapshot());
            var candidates = world.getChunkProvider()
                .getPossibleCreatures(EnumCreatureType.creature, x, platformY + 1, z);
            boolean nativeCandidate = false;
            if (candidates != null) for (Object value : candidates) if (value == registered) nativeCandidate = true;
            report.addProperty("actualProviderOffersOriginalEntry", nativeCandidate);
            report.addProperty("explicitNativeNeighborhoodChunks", 289);
            report.addProperty("creatureCapBefore", world.countEntities(EnumCreatureType.creature, true));
            report.addProperty("isolatedCreatureTables", tables.size());
            report.addProperty(
                "canSpawnAnimalsDuring",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            MinecraftForge.EVENT_BUS.register(this);
            evidence("prepared");
            helper.assertTrue(
                nativeCandidate,
                "Normal native chunk provider must offer the original registered entry; use horizonqa.world=normal");
        }

        void tick() {
            if (complete) return;
            if (!readyForSpawning()) return;
            report.add("nativeCapLatest", capSnapshot());
            helper.assertTrue(
                ++calls <= 240,
                "No eligible registered maid appeared within bounded native spawn attempts");
            nativeCall = true;
            try {
                spawner.findChunksForSpawning(world, false, true, true);
            } finally {
                nativeCall = false;
            }
            var natives = new JsonArray();
            for (var maid : joined) {
                if (!MaidSpawnChecks.present(maid)) continue;
                helper.assertTrue(
                    checked.containsKey(maid) && !maid.isTamed()
                        && maid.getHeldItem() == null
                        && maid.getOffhandItem() == null
                        && empty(maid),
                    "Native spawn was not a normal untamed empty maid");
                var raw = new NBTTagCompound();
                maid.writeToNBT(raw);
                var result = new JsonObject();
                result.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                result.addProperty("nativeRegistration", true);
                result.addProperty("nativeCheckSpawnObserved", true);
                result.add("completeNativeNbt", SleepAttributePortSnapshot.binaryNbt(raw));
                natives.add(result);
            }
            if (natives.size() == 0) {
                if (calls % 20 == 0) evidence("waiting");
                return;
            }
            report.add("nativeMaids", natives);
            report.addProperty("nativeCheckSpawnCount", checked.size());
            report.addProperty("nativeJoinCount", joined.size());
            complete = true;
            evidence("passed");
        }

        @SubscribeEvent
        public void checking(LivingSpawnEvent.CheckSpawn event) {
            if (nativeCall && event.world == world && event.entityLiving instanceof EntityMaid maid)
                checked.put(maid, Boolean.TRUE);
        }

        @SubscribeEvent
        public void joining(EntityJoinWorldEvent event) {
            if (worldgenCall && event.world == world && event.entity instanceof EntityMaid maid)
                worldgenJoined.add(maid);
            if (nativeCall && event.world == world
                && event.entity instanceof EntityMaid maid
                && checked.containsKey(maid)) joined.add(maid);
        }

        void close() {
            MinecraftForge.EVENT_BUS.unregister(this);
            var actors = new IdentityHashMap<EntityMaid, Boolean>(checked);
            for (var maid : joined) actors.put(maid, Boolean.TRUE);
            for (var maid : worldgenJoined) actors.put(maid, Boolean.TRUE);
            var removed = new JsonArray();
            boolean clean = true;
            for (var maid : actors.keySet()) {
                var row = new JsonObject();
                row.addProperty(
                    "uuid",
                    maid.getUniqueID()
                        .toString());
                if (maid.worldObj == world && MaidSpawnChecks.present(maid)) {
                    maid.setDead();
                    world.removePlayerEntityDangerously(maid);
                    boolean gone = world.getEntityByID(maid.getEntityId()) != maid && world.loadedEntityList.stream()
                        .noneMatch(value -> value == maid);
                    row.addProperty("exactOwnedActorRemoved", gone);
                    clean &= gone;
                } else {
                    boolean absent = maid.worldObj == world && world.getEntityByID(maid.getEntityId()) == null
                        && world.loadedEntityList.stream()
                            .noneMatch(value -> value == maid);
                    row.addProperty("alreadyAbsentOrNeverRegistered", absent);
                    row.addProperty("unknownSuccessorRetained", !absent);
                    clean &= absent;
                }
                removed.add(row);
            }
            report.add("exactActorCleanup", removed);
            report.addProperty("exactActorsCleaned", clean);
            for (var table : tables.entrySet()) {
                table.getKey()
                    .clear();
                table.getKey()
                    .addAll(table.getValue());
            }
            try {
                for (var block : blocks) world.setBlock(block.x, block.y, block.z, block.block, block.meta, 2);
                report.add("platformChunksBeforeRelease", chunkSnapshot());
            } finally {
                platformChunks.releaseAll();
            }
            world.setWorldTime(originalTime + world.getTotalWorldTime() - started);
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", originalMobSpawning);
            if (fixture.owner.worldObj == world && world.getEntityByID(fixture.owner.getEntityId()) == fixture.owner
                && world.playerEntities.stream()
                    .anyMatch(value -> value == fixture.owner))
                world.removePlayerEntityDangerously(fixture.owner);
            boolean playerAbsent = world.playerEntities.stream()
                .noneMatch(value -> value == fixture.owner)
                && world.loadedEntityList.stream()
                    .noneMatch(value -> value == fixture.owner);
            report.addProperty("exactOwnedPlayerRemoved", playerAbsent);
            clean &= playerAbsent;
            report.addProperty(
                "spawnTablesRestored",
                tables.entrySet()
                    .stream()
                    .allMatch(
                        e -> e.getKey()
                            .equals(e.getValue())));
            evidence(complete && clean ? "passed-and-restored" : "failed-and-restored");
            helper.assertTrue(clean, "Native spawn cleanup retained an unknown or partially registered successor");
        }

        void evidence(String state) {
            report.addProperty("state", state);
            report.addProperty("nativeSpawnerCalls", calls);
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "acquisition-" + mechanism + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain native acquisition evidence", failure);
            }
        }

        boolean readyForSpawning() {
            long now = world.getTotalWorldTime();
            if (lastLightingTick == now) return false;
            lastLightingTick = now;
            lightingTicks++;
            report.add("platformChunksBeforeLightingReads", chunkSnapshot());
            boolean retained = true;
            for (var entry : retainedChunks.entrySet()) {
                var key = entry.getKey();
                retained &= world.getChunkProvider()
                    .chunkExists(key.chunkXPos, key.chunkZPos) && entry.getValue().isChunkLoaded
                    && world.getChunkFromChunkCoords(key.chunkXPos, key.chunkZPos) == entry.getValue();
            }
            if (!retained) evidence("platform-ticket-lost-before-lighting");
            helper.assertTrue(
                retained,
                "Native platform chunk identity was lost despite its held Forge ticket; no diagnostic reload fallback");
            boolean ready = true;
            var samples = new JsonArray();
            for (var offset : new int[][] { { 0, 0 }, { 31, 0 }, { 0, 31 }, { 31, 31 }, { 16, 16 } }) {
                int x = platformX + offset[0], z = platformZ + offset[1], y = platformY + 1;
                var row = new JsonObject();
                row.addProperty("x", x);
                row.addProperty("y", y);
                row.addProperty("z", z);
                int light = world.getFullBlockLightValue(x, y, z);
                boolean sky = world.canBlockSeeTheSky(x, y, z);
                int top = world.getTopSolidOrLiquidBlock(x, z);
                row.addProperty("fullLight", light);
                row.addProperty("skyLight", world.getSavedLightValue(EnumSkyBlock.Sky, x, y, z));
                row.addProperty("blockLight", world.getSavedLightValue(EnumSkyBlock.Block, x, y, z));
                row.addProperty("canSeeSky", sky);
                row.addProperty("top", top);
                row.addProperty("height", world.getHeightValue(x, z));
                ready &= light > 8 && sky
                    && top == y
                    && world.getBlock(x, y - 1, z) == Blocks.grass
                    && world.isAirBlock(x, y, z)
                    && world.isAirBlock(x, y + 1, z);
                samples.add(row);
            }
            stableLightTicks = ready ? stableLightTicks + 1 : 0;
            report.add("nativePlatformLighting", samples);
            report.add("platformChunksDuringLighting", chunkSnapshot());
            report.addProperty("lightingTicks", lightingTicks);
            report.addProperty("stableLightTicks", stableLightTicks);
            report.addProperty("worldTime", world.getWorldTime());
            if (lightingTicks == 1 || lightingTicks % 20 == 0 || stableLightTicks == 2)
                evidence("native-lighting-wait");
            helper.assertTrue(
                lightingTicks <= 200 || stableLightTicks >= 2,
                "Native grass/sky/light did not stabilize within 200 actual world ticks; no light value was overridden");
            return stableLightTicks >= 2;
        }

        JsonArray chunkSnapshot() {
            var result = new JsonArray();
            for (var entry : retainedChunks.entrySet()) {
                int x = entry.getKey().chunkXPos, z = entry.getKey().chunkZPos;
                boolean exists = world.getChunkProvider()
                    .chunkExists(x, z);
                var row = new JsonObject();
                row.addProperty("x", x);
                row.addProperty("z", z);
                row.addProperty("loaded", exists && entry.getValue().isChunkLoaded);
                // provideChunk must not be used here when absent: a diagnostic must not silently reload a lost
                // platform.
                row.addProperty("sameRetainedChunk", exists && world.getChunkFromChunkCoords(x, z) == entry.getValue());
                result.add(row);
            }
            return result;
        }

        JsonObject capSnapshot() {
            var chunks = new HashSet<ChunkCoordIntPair>();
            for (Object value : world.playerEntities) {
                var player = (net.minecraft.entity.player.EntityPlayer) value;
                int x = net.minecraft.util.MathHelper.floor_double(player.posX / 16);
                int z = net.minecraft.util.MathHelper.floor_double(player.posZ / 16);
                // Exact native 17x17 union, including border entries, used by SpawnerAnimals' cap expression.
                for (int dx = -8; dx <= 8; dx++)
                    for (int dz = -8; dz <= 8; dz++) chunks.add(new ChunkCoordIntPair(x + dx, z + dz));
            }
            var result = new JsonObject();
            int current = world.countEntities(EnumCreatureType.creature, true);
            int maximum = EnumCreatureType.creature.getMaxNumberOfCreature() * chunks.size() / 256;
            result.addProperty("current", current);
            result.addProperty("eligibleChunkUnion", chunks.size());
            result.addProperty("nativeMaximum", maximum);
            result.addProperty("nativeCapAllowsAttempt", current <= maximum);
            result.addProperty("noCreaturesRemoved", true);
            return result;
        }

        private static boolean empty(EntityMaid maid) {
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
                if (inventory.getStackInSlot(slot) != null) return false;
            return true;
        }
    }

    private static final class SavedBlock {

        final int x, y, z, meta;
        final Block block;

        SavedBlock(int x, int y, int z, Block block, int meta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
        }
    }
}
