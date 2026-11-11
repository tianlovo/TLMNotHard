package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileSource;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.life.bed.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;
import com.tianluoqaq.tlmnh.common.life.meal.MaidMealController;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Private native provider tests. The old object is never removed/recreated to simulate a chunk boundary. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NativeChunkLifecycleTests {

    private NativeChunkLifecycleTests() {}

    private static Scene active;

    @GameTest(template = "modern_core_cell", timeoutTicks = 800, batch = "native_chunk_food")
    public static void nativeChunkRebuildPreservesInterruptedMealAndOriginalHands(GameTestHelper h) throws Exception {
        start(h, "food");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 650, batch = "native_chunk_bed")
    public static void nativeChunkRebuildReleasesBedLeaseBeforeItsExpiry(GameTestHelper h) throws Exception {
        start(h, "bed");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 800, batch = "native_chunk_shooter")
    public static void actualShooterChunkExitAndArrowChunkRebuildRetainStoppedSource(GameTestHelper h)
        throws Exception {
        start(h, "shooter");
    }

    private static void start(GameTestHelper h, String kind) throws Exception {
        require(active == null, "Native chunk scenarios must run serially");
        var scene = new Scene(h, kind);
        active = scene;
        h.afterTest(scene::close); // Register before preparation; its exact objects/blocks are kept on failure.
        scene.setup();
        h.onEachTick("native unload/save/load and natural activity", () -> {
            try {
                scene.tick();
            } catch (Exception | LinkageError | AssertionError failure) {
                if (scene.failure == null) scene.failure = failure;
                if (!scene.report.has("primaryFailure")) scene.report.addProperty("primaryFailure", failure.toString());
                scene.capture("before-cleanup");
                throw new IllegalStateException("Actual native chunk lifecycle failed: " + kind, failure);
            }
        });
        h.succeedWhen(() -> scene.complete);
    }

    public static final class Scene {

        final GameTestHelper h;
        final String kind, run = UUID.randomUUID()
            .toString();
        final UUID owner = UUID.randomUUID();
        UUID maidId;
        final WorldServer world;
        final ChunkProviderServer provider;
        final GameTestChunkLoader sourceTickets = new GameTestChunkLoader(), otherTickets = new GameTestChunkLoader(),
            reloadedTickets = new GameTestChunkLoader();
        final List<Entity> owned = new ArrayList<>();
        final List<Cycle> cycles = new ArrayList<>();
        final List<ChangedBlock> changed = new ArrayList<>();
        final JsonObject report = new JsonObject();
        final JsonArray events = new JsonArray(), frames = new JsonArray(), cleanup = new JsonArray();
        final long oldTime;
        final String oldDaylight, oldSpawning;
        final int oldWorkRange = MaidHomeConfig.workRange, oldSleepRange = MaidHomeConfig.sleepRange;
        final int x, y = 191, z, cx, cz;
        EntityMaid maid;
        EntityPig target;
        EntityArrow arrow;
        TileEntityMaidBed bed;
        NBTTagCompound baselineSlots, finalSlots, expectedRecovery, opaque;
        MaidFoodProfile nutrition;
        Cycle cycle;
        int phase = -1, samples, reloadTick, targetHurts;
        Object scheduler;
        java.lang.reflect.Method protectsChunk;
        float firstMealHealth, targetHealth;
        boolean complete, closed;
        Throwable failure;

        Scene(GameTestHelper h, String kind) {
            this.h = h;
            this.kind = kind;
            world = (WorldServer) h.getWorld();
            require(world.getChunkProvider() instanceof ChunkProviderServer, "Native ChunkProviderServer required");
            provider = (ChunkProviderServer) world.getChunkProvider();
            var origin = h.absolute(0, 0, 0);
            int mechanismOffset = kind.equals("food") ? 0 : kind.equals("bed") ? 256 : 512;
            x = (origin.x() + 2048 + mechanismOffset) & ~15;
            z = (origin.z() + 2048) & ~15;
            cx = x >> 4;
            cz = z >> 4;
            oldTime = world.getWorldTime();
            oldDaylight = world.getGameRules()
                .getGameRuleStringValue("doDaylightCycle");
            oldSpawning = world.getGameRules()
                .getGameRuleStringValue("doMobSpawning");
        }

        void setup() throws Exception {
            require(
                Boolean.getBoolean("tlmnh.devCommands") && world.provider.dimensionId == 0 && !world.levelSaving,
                "Explicit development overworld with native saving required");
            require(
                "normal".equals(System.getProperty("horizonqa.world"))
                    && "interactive".equals(System.getProperty("horizonqa.mode"))
                    && "1".equals(System.getProperty("horizonqa.turbo")),
                "Normal world/native server policy/turbo1 required");
            require(
                MinecraftServer.getServer()
                    .getCanSpawnAnimals() && MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
                "Normal server animals and startup Enhanced configuration required; no development selection is made");
            require(
                DependencyFingerprint.mod("hodgepodge")
                    .equals("2.7.221:sha256:9b0bfb7e84a66387319952d76e6f114d6218a988e04d303965c31efb2165e824"),
                "Reviewed actual native unload provider required");
            require(provider.currentChunkLoader instanceof AnvilChunkLoader, "Actual native Anvil loader required");
            var spawn = world.getSpawnPoint();
            require(
                Math.abs(x + 8 - spawn.posX) > 128 || Math.abs(z + 8 - spawn.posZ) > 128,
                "Remote test chunk is inside spawn retention");
            for (int a = cx - 1; a <= cx + 2; a++) for (int b = cz - 1; b <= cz + 1; b++) require(
                !world.getPersistentChunks()
                    .containsKey(new ChunkCoordIntPair(a, b)),
                "Foreign forced chunk is retained; no ticket is cleared");
            world.getGameRules()
                .setOrCreateGameRule("doDaylightCycle", "false");
            world.getGameRules()
                .setOrCreateGameRule("doMobSpawning", "false");
            world.setWorldTime(6000);
            MaidHomeConfig.workRange = 16;
            MaidHomeConfig.sleepRange = 6;
            sourceTickets.forceChunksStrict(world, x - 16, y, z - 16, x + 47, y + 5, z + 31);
            otherTickets.forceChunksStrict(world, x + 16, y, z - 16, x + 47, y + 5, z + 31);
            for (int a = 0; a < 32; a++) for (int b = 0; b < 16; b++) {
                for (int c = 0; c < 4; c++)
                    require(world.isAirBlock(x + a, y + c, z + b), "Unknown remote platform source retained");
                place(x + a, y, z + b, Blocks.iron_block, 0);
            }
            opaque = new NBTTagCompound();
            opaque.setString("FutureOpaque", "chunk-native-" + run);
            opaque.setIntArray("UnknownArray", new int[] { -1, 65537, 17 });
            maid = new EntityMaid(world);
            maidId = maid.getUniqueID();
            require(maid.bindTo(owner), "Fresh private owner binding rejected");
            maid.setModelId("geckolib:winefox_hanfu");
            maid.setPickup(false);
            maid.setFavorability(74);
            maid.getFavorabilityManager()
                .refreshAttributes();
            maid.setSchedule(kind.equals("bed") ? MaidSchedule.NIGHT : MaidSchedule.ALL);
            maid.setTask(kind.equals("shooter") ? MaidTask.RANGED_ATTACK : MaidTask.IDLE);
            maid.setSitting(kind.equals("shooter"));
            maid.setHunger(100);
            maid.setExperience(37);
            maid.setPosition(x + (kind.equals("shooter") ? 14.5 : 7.5), y + 1, z + 7.5);
            var home = maid.getHomeArea();
            require(
                home.setEnabled(true) && home.capture(Activity.WORK)
                    && home.capture(Activity.IDLE)
                    && home.capture(Activity.REST),
                "Native home input rejected");
            for (int i = 0; i < maid.getMaidInventory()
                .getSizeInventory(); i++) {
                var item = i >= MaidInventory.ARMOR_START ? new ItemStack(
                    new Item[] { Items.iron_boots, Items.iron_leggings, Items.iron_chestplate, Items.iron_helmet }[i
                        - MaidInventory.ARMOR_START])
                    : new ItemStack(Items.diamond, i + 1);
                if (i == MaidInventory.MAIN_HAND)
                    item = new ItemStack(kind.equals("shooter") ? Items.bow : Items.iron_axe, 1, 3);
                if (i == MaidInventory.OFF_HAND) item = new ItemStack(Items.stick, 2);
                if (i == 0 && kind.equals("food")) item = new ItemStack(Items.mushroom_stew);
                if (i == 0 && kind.equals("shooter")) item = new ItemStack(Items.arrow, 4);
                var tag = (NBTTagCompound) opaque.copy();
                tag.setInteger("OriginalSlot", i);
                item.setTagCompound(tag);
                maid.getMaidInventory()
                    .setInventorySlotContents(i, item);
            }
            maid.getMaidInventory()
                .recovery()
                .retain(opaque);
            maid.getEntityData()
                .setString("TLMNHChunkRun", run);
            maid.getEntityData()
                .setTag("OpaqueChunkPayload", opaque.copy());
            var seed = nativeNbt(maid);
            seed.getCompoundTag("TLMNH")
                .setTag("UnknownChunkPayload", opaque.copy());
            maid.readFromNBT(seed);
            expectedRecovery = new NBTTagCompound();
            expectedRecovery.setTag(
                "Items",
                maid.getMaidInventory()
                    .recovery()
                    .write());
            baselineSlots = slots(maid);
            finalSlots = (NBTTagCompound) baselineSlots.copy();
            if (kind.equals("food")) {
                nutrition = MaidFoodProfile.inspect(
                    maid.getMaidInventory()
                        .getStackInSlot(0));
                require(
                    nutrition != null && nutrition.total > 0 && nutrition.duration >= 8 && nutrition.duration < 100,
                    "Supported native soup nutrition and use duration required");
                report.addProperty("actualSoupNutrition", nutrition.total);
                report.addProperty("actualSoupUseDuration", nutrition.duration);
                maid.setHealth(2);
                firstMealHealth = Math.min(maid.getMaxHealth(), 2 + Math.max(nutrition.total / 5, 1));
                require(
                    maid.getMaxHealth() - firstMealHealth >= 2,
                    "Interrupted start must leave a legitimate healing input after rebuild");
            }
            if (kind.equals("bed")) {
                place(x + 7, y + 1, z + 8, MaidLifeContent.maidBed, 2);
                place(x + 7, y + 1, z + 7, MaidLifeContent.maidBed, 10);
                bed = (TileEntityMaidBed) world.getTileEntity(x + 7, y + 1, z + 7);
                require(bed != null && bed.complete(), "Complete native bed required");
            }
            owned.add(maid);
            if (kind.equals("shooter")) {
                target = new EntityPig(world);
                target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                    .setBaseValue(200);
                target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .setBaseValue(0);
                target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                    .setBaseValue(1);
                target.setHealth(200);
                target.setPosition(x + 25.5, y + 1, z + 7.5);
                target.getEntityData()
                    .setString("TLMNHChunkRun", run);
                owned.add(target);
                maid.getAttackListData()
                    .attackGroups()
                    .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
            }
            var schedulerType = Class.forName("com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler");
            scheduler = schedulerType.getMethod("getForDimension", int.class)
                .invoke(null, 0);
            require(scheduler != null, "Existing actual generation scheduler required; no substitute is created");
            protectsChunk = schedulerType.getMethod("shouldProtectFromUnload", long.class);
            require(protectsChunk.getReturnType() == boolean.class, "Actual unload eligibility query shape changed");
            MinecraftForge.EVENT_BUS.register(this);
            report.addProperty("schema", "native-chunk-lifecycle-v1");
            report.addProperty("kind", kind);
            report.addProperty("run", run);
            report.addProperty("naturalDevelopmentSelectionUsed", false);
            report.addProperty("manualActorTickOrOnUnloadUsed", false);
            report.addProperty("actualNetworkPlayerClaimed", false);
            report.addProperty("arbitraryCrashAtomicityClaimed", false);
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    EntityMaid.class,
                    MaidBrainLifecycle.class,
                    MaidBrainCoordinator.class,
                    MaidPolicyRuntime.class,
                    MaidMealController.class,
                    TileEntityMaidBed.class,
                    MaidProjectileSource.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(NativeChunkLifecycleTests.class, Scene.class, Cycle.class));
            report.add("events", events);
            report.add("frames", frames);
            report.add("cleanup", cleanup);
        }

        void tick() throws Exception {
            if (closed || complete) return;
            if (failure != null) throw new IllegalStateException("Earlier native event observation failed", failure);
            samples++;
            sample();
            require(
                !world.levelSaving && MinecraftServer.getServer()
                    .getCanSpawnAnimals(),
                "Native saving/animal policy changed during this scenario");
            for (var previous : cycles) previous.checkRetired();
            if (phase == -1) {
                if ((Boolean) protectsChunk.invoke(scheduler, ChunkCoordIntPair.chunkXZ2Int(cx, cz))) return;
                require(world.spawnEntityInWorld(maid), "Native initial actor registration rejected");
                if (target != null) require(world.spawnEntityInWorld(target), "Native ranged target rejected");
                phase = 0;
                return;
            }
            if (cycle != null && !cycle.done) {
                cycle.tick();
                return;
            }
            if (phase == 0) {
                require(present(maid), "Natural initial actor exited unexpectedly");
                var runtime = maid.getBrainCoordinator()
                    .peekPolicyRuntime();
                if (runtime == null || !runtime.isReady() || runtime.modernDispatchCount() == 0) return;
                require(
                    runtime.getProfile() == MaidBrainProfile.ENHANCED && runtime.legacyDispatchCount() == 0,
                    "Normal configured Enhanced required");
                if (kind.equals("food")) {
                    if (!maid.getMealController()
                        .isEating()) return;
                    require(
                        (maid.getHealth() == 2 || maid.getHealth() == firstMealHealth)
                            && count(Items.mushroom_stew) == 1
                            && count(Items.bowl) == 0,
                        "Native meal start health/material semantics changed");
                    firstMealHealth = maid.getHealth();
                    require(
                        maid.getFavorabilityManager()
                            .canAdd(Type.WORK_MEAL.getTypeName()),
                        "Normal N healing start unexpectedly spent the independent work-meal cooldown");
                    report.addProperty(
                        "workMealReadyAtInterruptedStart",
                        maid.getFavorabilityManager()
                            .canAdd(Type.WORK_MEAL.getTypeName()));
                    report.add(
                        "cooldownAtInterruptedStart",
                        binary(nativeNbt(maid).getCompoundTag("FavorabilityManagerCounter")));
                } else if (kind.equals("bed")) {
                    if (!maid.hasPose(MaidPose.SLEEPING) || !bed.isOwnedBy(maid)) return;
                } else {
                    var core = maid.getBrainCoordinator()
                        .peekEnhancedCore();
                    if (core == null) return;
                    arrow = core.actor()
                        .rangedBow()
                        .getLastArrow();
                    if (arrow == null || arrow.isDead || !present(arrow) || arrow.chunkCoordX == cx) return;
                    require(
                        arrow.shootingEntity == maid && ((MaidArrowSourceState) arrow).tlmnh$source() != null
                            && !((MaidArrowSourceState) arrow).tlmnh$sourceStopped(),
                        "Actual naturally released registered arrow required");
                    owned.add(arrow);
                    targetHealth = target.getHealth();
                    // Public normal task input after the paid release; no legal new-shot workload is mislabeled as
                    // duplication.
                    maid.setTask(MaidTask.IDLE);
                    finalSlots = slots(maid);
                    require(
                        count(Items.arrow) == 3 && core.actor()
                            .rangedBow()
                            .getShots() == 1,
                        "One natural arrow must have paid exactly once");
                    report
                        .add("paidArrowSource", binary((NBTTagCompound) ((MaidArrowSourceState) arrow).tlmnh$source()));
                }
                phase = 1;
                cycle = new Cycle(this, maid, "actor", sourceTickets);
                return;
            }
            if (phase == 1) {
                maid = (EntityMaid) cycle.reloaded;
                if (!kind.equals("food"))
                    require(slots(maid).equals(finalSlots), "Native rebuilt canonical12 slots changed");
                // Food may already have legitimately restarted after the first-load canonical-slot assertion.
                unchanged(maid);
                runtime(maid);
                reloadTick = samples;
                if (kind.equals("bed")) {
                    require(
                        cycle.unloadWorldTick >= 0 && world.getTotalWorldTime() - cycle.unloadWorldTick < 60
                            && cycle.firstBedLeaseEmpty,
                        "No before-expiry proof that native tile reconstruction cleared its old bed lease");
                    bed = (TileEntityMaidBed) world.getTileEntity(x + 7, y + 1, z + 7);
                }
                phase = 2;
                return;
            }
            if (kind.equals("food")) {
                require(maid.getMaxHealth() - firstMealHealth >= 2, "Reload healing input is no longer legal");
                if (count(Items.mushroom_stew) == 0 && count(Items.bowl) == 1
                    && !maid.getMealController()
                        .isEating()) {
                    var expected = (NBTTagCompound) baselineSlots.copy();
                    expected.setTag("Slot0", ProtectedItemCodec.encode(new ItemStack(Items.bowl)));
                    require(
                        expected.equals(slots(maid)),
                        "Interrupted meal duplicated, lost or misplaced source/returned container");
                    finalSlots = expected;
                    report.addProperty("firstStartHealth", firstMealHealth);
                    report.addProperty("afterNormalReentryHealth", maid.getHealth());
                    finish();
                }
            } else if (kind.equals("bed")) {
                if (phase == 2 && maid.hasPose(MaidPose.SLEEPING) && bed.isOwnedBy(maid)) {
                    require(slots(maid).equals(finalSlots), "Native sleep reuse changed original12 slots");
                    report.addProperty("normalSleepReacquired", true);
                    maid.setSchedule(MaidSchedule.ALL);
                    phase = 3;
                } else if (phase == 3 && !maid.hasPose(MaidPose.SLEEPING) && !bed.isOwnedBy(maid)) {
                    report.addProperty("normalScheduleWakeReleased", true);
                    finish();
                }
            } else if (phase == 2) {
                require(
                    !arrow.isDead && ((MaidArrowSourceState) arrow).tlmnh$sourceStopped()
                        && target.getHealth() == targetHealth
                        && targetHurts == 0,
                    "Old arrow did not stop before damage when its actual source left the world indexes");
                require(
                    maid.getTask() == MaidTask.IDLE && slots(maid).equals(finalSlots),
                    "Standby after rebuild paid for another shot");
                report.add("stoppedArrowBeforeItsOwnUnload", binary(nativeNbt(arrow)));
                phase = 3;
                cycle = new Cycle(this, arrow, "stopped-arrow", otherTickets);
            } else {
                arrow = (EntityArrow) cycle.reloaded;
                require(
                    ((MaidArrowSourceState) arrow).tlmnh$sourceStopped() && !arrow.isDead
                        && slots(maid).equals(finalSlots),
                    "Native reloaded arrow resumed or source inventory changed");
                if (phase == 3) {
                    phase = 4;
                    reloadTick = samples;
                }
                require(
                    target.getHealth() == targetHealth && targetHurts == 0,
                    "Stopped reconstructed arrow caused target damage");
                if (samples - reloadTick >= 20) {
                    report.add("stoppedArrowAfterReload", binary(nativeNbt(arrow)));
                    finish();
                }
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void fixedTarget(LivingEvent.LivingUpdateEvent e) {
            if (!closed && e.entityLiving == target) e.setCanceled(true);
        }

        @SubscribeEvent
        public void hurt(LivingHurtEvent e) {
            if (!closed && e.entityLiving == target && arrow != null && e.source.getSourceOfDamage() == arrow)
                targetHurts++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void unload(ChunkEvent.Unload e) {
            observeEvent("unload", () -> unloading(e));
        }

        private void unloading(ChunkEvent.Unload e) {
            if (cycle != null && e.world == world && e.getChunk() == cycle.beforeChunk) {
                cycle.sawUnload = true;
                cycle.unloadWorldTick = world.getTotalWorldTime();
                event("native-unload", e.getChunk());
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void saving(ChunkDataEvent.Save e) {
            observeEvent("save", () -> saved(e));
        }

        private void saved(ChunkDataEvent.Save e) {
            if (cycle == null || e.world != world || e.getChunk() != cycle.beforeChunk || !cycle.sawUnload) return;
            var level = e.getData()
                .getCompoundTag("Level");
            cycle.savedEntity = find(level.getTagList("Entities", 10), cycle.id);
            cycle.savedLevel = (NBTTagCompound) level.copy();
            event("native-chunk-save-after-unload", e.getChunk());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void loading(ChunkEvent.Load e) {
            observeEvent("load", () -> loaded(e));
        }

        private void loaded(ChunkEvent.Load e) {
            if (cycle == null || e.world != world
                || e.getChunk().xPosition != cycle.chunkX
                || e.getChunk().zPosition != cycle.chunkZ
                || !cycle.diskConfirmed) return;
            cycle.loadedChunk = e.getChunk();
            event("native-load", e.getChunk());
            for (var list : e.getChunk().entityLists)
                for (Object raw : list) if (raw instanceof Entity entity && entity.getUniqueID()
                    .equals(cycle.id)) {
                        require(
                            cycle.reloaded == null && entity != cycle.before
                                && (run.equals(
                                    entity.getEntityData()
                                        .getString("TLMNHChunkRun"))
                                    || entity instanceof EntityArrow
                                        && cycle.savedEntity.getTag(MaidProjectileSource.TAG)
                                            .equals(((MaidArrowSourceState) entity).tlmnh$source())),
                            "Unknown or duplicate reconstructed subject retained");
                        cycle.reloaded = entity;
                        if (entity instanceof EntityMaid next) {
                            require(
                                slots(next).equals(finalSlots),
                                "First native load changed12 slots before any AI tick");
                            unchanged(next);
                            require(
                                next.getItemUseState()
                                    .peek() == null && !next.hasPose(MaidPose.SLEEPING),
                                "Native reload retained a transient item/sleep lease");
                            var firstRaw = nativeNbt(next);
                            require(
                                firstRaw.getCompoundTag("TLMNH")
                                    .equals(cycle.savedEntity.getCompoundTag("TLMNH"))
                                    && firstRaw.getCompoundTag("ForgeData")
                                        .equals(cycle.savedEntity.getCompoundTag("ForgeData")),
                                "Complete owned extension/opaque ForgeData changed during native reconstruction");
                            report.add("firstReconstructedMaidNativeNbt", binary(firstRaw));
                            if (kind.equals("food")) {
                                require(
                                    next.getHealth() == firstMealHealth && next.getMaxHealth() - next.getHealth() >= 2
                                        && next.getFavorabilityManager()
                                            .canAdd(Type.WORK_MEAL.getTypeName()),
                                    "Disk reload lacks the documented legitimate healing/cooldown condition");
                                report.addProperty("reloadHealthBeforeAnyUpdate", next.getHealth());
                                report.addProperty(
                                    "reloadWorkMealReadyBeforeAnyUpdate",
                                    next.getFavorabilityManager()
                                        .canAdd(Type.WORK_MEAL.getTypeName()));
                                report.add(
                                    "reloadCooldownBeforeAnyUpdate",
                                    binary(nativeNbt(next).getCompoundTag("FavorabilityManagerCounter")));
                            }
                            if (kind.equals("bed")) {
                                var tile = e.getChunk().chunkTileEntityMap.values();
                                for (Object value : tile)
                                    if (value instanceof TileEntityMaidBed nextBed && nextBed.xCoord == x + 7
                                        && nextBed.zCoord == z + 7) {
                                            cycle.firstBedLeaseEmpty = nextBed != bed && !nextBed.isOwnedBy(next)
                                                && nextBed.available(next);
                                            var tileRaw = new NBTTagCompound();
                                            nextBed.writeToNBT(tileRaw);
                                            var savedTiles = cycle.savedLevel.getTagList("TileEntities", 10);
                                            NBTTagCompound originalTile = null;
                                            for (int i = 0; i < savedTiles.tagCount(); i++) {
                                                var saved = savedTiles.getCompoundTagAt(i);
                                                if (saved.getInteger("x") == nextBed.xCoord
                                                    && saved.getInteger("y") == nextBed.yCoord
                                                    && saved.getInteger("z") == nextBed.zCoord) {
                                                    require(originalTile == null, "Duplicate saved bed tile");
                                                    originalTile = saved;
                                                }
                                            }
                                            require(
                                                originalTile != null && originalTile.equals(tileRaw),
                                                "Native tile rebuild lost complete persistent bed payload");
                                            report.addProperty("firstBedLoadWorldTick", world.getTotalWorldTime());
                                            report.addProperty("firstBedLeaseEmpty", cycle.firstBedLeaseEmpty);
                                            report.add("firstBedLoadNativeNbt", binary(tileRaw));
                                        }
                            }
                        }
                        owned.add(entity);
                    }
            if (target != null) for (var list : e.getChunk().entityLists)
                for (Object raw : list) if (raw instanceof EntityPig next && next != target
                    && next.getUniqueID()
                        .equals(target.getUniqueID())) {
                            require(
                                run.equals(
                                    next.getEntityData()
                                        .getString("TLMNHChunkRun")),
                                "Unknown reconstructed target retained");
                            var saved = find(cycle.savedLevel.getTagList("Entities", 10), next.getUniqueID());
                            var actual = nativeNbt(next);
                            actual.setString("id", net.minecraft.entity.EntityList.getEntityString(next));
                            require(saved.equals(actual), "Reconstructed original target payload changed");
                            owned.add(next);
                            target = next;
                        }
        }

        private void observeEvent(String operation, Runnable callback) {
            if (closed || failure != null) return;
            try {
                callback.run();
            } catch (RuntimeException | LinkageError | AssertionError error) {
                failure = error;
                report.addProperty("primaryFailure", operation + ": " + error);
                // Observe fail-closed without interrupting the native backend's save/load transaction.
            }
        }

        void unchanged(EntityMaid next) {
            require(
                next.getUniqueID()
                    .equals(maidId)
                    && next.func_152113_b()
                        .equals(owner.toString())
                    && run.equals(
                        next.getEntityData()
                            .getString("TLMNHChunkRun"))
                    && next.getModelId()
                        .equals("geckolib:winefox_hanfu"),
                "Persistent identity/owner/model changed");
            var raw = nativeNbt(next);
            require(next.getExperience() == 37, "Original nonzero experience changed");
            require(
                raw.getCompoundTag("TLMNH")
                    .getCompoundTag("UnknownChunkPayload")
                    .equals(opaque)
                    && next.getEntityData()
                        .getCompoundTag("OpaqueChunkPayload")
                        .equals(opaque)
                    && next.getMaidInventory()
                        .recovery()
                        .write()
                        .equals(expectedRecovery.getTagList("Items", 10)),
                "Unknown entity/Recovery data changed");
        }

        void finish() {
            unchanged(maid);
            require(slots(maid).equals(finalSlots), "Final inventory changed");
            complete = true;
            report.addProperty("passedBeforeCleanup", true);
            capture("business-complete");
        }

        void event(String type, Chunk chunk) {
            var row = new JsonObject();
            row.addProperty("event", type);
            row.addProperty("worldTick", world.getTotalWorldTime());
            row.addProperty("chunkX", chunk.xPosition);
            row.addProperty("chunkZ", chunk.zPosition);
            row.addProperty(
                "thread",
                Thread.currentThread()
                    .getName());
            events.add(row);
        }

        void sample() {
            var row = new JsonObject();
            row.addProperty("sample", samples);
            row.addProperty("phase", phase);
            row.addProperty("worldTick", world.getTotalWorldTime());
            var original = (Chunk) provider.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(cx, cz));
            if (original != null) {
                row.addProperty("terrainPopulated", original.isTerrainPopulated);
                row.addProperty("lightPopulated", original.isLightPopulated);
            }
            row.addProperty("sourceLoaded", provider.chunkExists(cx, cz));
            row.addProperty(
                "sourceForced",
                world.getPersistentChunks()
                    .containsKey(new ChunkCoordIntPair(cx, cz)));
            if (maid != null) {
                row.addProperty("maidPresent", present(maid));
                row.addProperty("health", maid.getHealth());
                row.addProperty(
                    "eating",
                    maid.getMealController()
                        .isEating());
                row.addProperty("sleeping", maid.hasPose(MaidPose.SLEEPING));
            }
            frames.add(row);
        }

        void capture(String label) {
            try {
                report.addProperty("latestLabel", label);
                if (maid != null) report.add("latestMaidNbt", binary(nativeNbt(maid)));
                write();
            } catch (Exception saveFailure) {
                if (failure != null) failure.addSuppressed(saveFailure);
                else throw new IllegalStateException(saveFailure);
            }
        }

        void write() throws Exception {
            var root = Paths.get(System.getProperty("horizonqa.reportDir"))
                .toRealPath();
            Files.write(
                root.resolve("native-chunk-" + kind + ".json"),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        }

        int count(Item item) {
            int total = 0;
            for (int i = 0; i < maid.getMaidInventory()
                .getSizeInventory(); i++) {
                var stack = maid.getMaidInventory()
                    .getStackInSlot(i);
                if (stack != null && stack.getItem() == item) total += stack.stackSize;
            }
            return total;
        }

        void place(int a, int b, int c, Block block, int meta) {
            var original = new ChangedBlock(
                a,
                b,
                c,
                world.getBlock(a, b, c),
                world.getBlockMetadata(a, b, c),
                block,
                meta);
            changed.add(original);
            require(
                world.setBlock(a, b, c, block, meta, 2) && world.getBlock(a, b, c) == block
                    && world.getBlockMetadata(a, b, c) == meta,
                "Native owned block placement/readback failed");
        }

        void close() {
            if (closed) return;
            capture("before-cleanup");
            closed = true;
            Throwable problem = null;
            try {
                for (var entity : owned) if (present(entity)) {
                    require(entity.worldObj == world, "Unknown entity world retained");
                    if (entity instanceof EntityMaid next) {
                        unchanged(next);
                        next.getBrainCoordinator()
                            .setSuspended(true);
                        require(
                            slots(next).equals(finalSlots),
                            "Unknown live inventory successor retained during cleanup");
                    }
                    if (entity instanceof EntityArrow output) require(
                        output instanceof MaidArrowSourceState && ((MaidArrowSourceState) output).tlmnh$sourceStopped()
                            && cycle != null
                            && cycle.savedEntity != null
                            && cycle.savedEntity.getTag(MaidProjectileSource.TAG)
                                .equals(((MaidArrowSourceState) output).tlmnh$source()),
                        "Unknown active/rewritten arrow successor retained during cleanup");
                    if (entity instanceof EntityPig) require(
                        run.equals(
                            entity.getEntityData()
                                .getString("TLMNHChunkRun")),
                        "Unknown ranged target successor retained");
                    entity.setDead();
                    world.removePlayerEntityDangerously(entity);
                    var row = new JsonObject();
                    row.addProperty(
                        "removedExactObject",
                        entity.getUniqueID()
                            .toString());
                    cleanup.add(row);
                }
                for (int i = changed.size() - 1; i >= 0; i--) {
                    var block = changed.get(i);
                    // Unbroken fixture bed plus its two supports stay in this unique disposable QA world; no synthetic
                    // break/drop cleanup.
                    if (kind.equals("bed") && block.x == x + 7 && (block.z == z + 7 || block.z == z + 8)) continue;
                    if (!provider.chunkExists(block.x >> 4, block.z >> 4)) {
                        report.addProperty("unloadedFixtureSourceRetained", true);
                        continue;
                    }
                    require(
                        world.getBlock(block.x, block.y, block.z) == block.after
                            && world.getBlockMetadata(block.x, block.y, block.z) == block.afterMeta,
                        "Unknown remote block successor retained");
                    require(
                        world.setBlock(block.x, block.y, block.z, block.before, block.beforeMeta, 2),
                        "Native fixture restore failed");
                }
            } catch (RuntimeException | Error error) {
                problem = error;
                report.addProperty("cleanupFailure", error.toString());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(this);
                sourceTickets.releaseAll();
                otherTickets.releaseAll();
                reloadedTickets.releaseAll();
                MaidHomeConfig.workRange = oldWorkRange;
                MaidHomeConfig.sleepRange = oldSleepRange;
                world.getGameRules()
                    .setOrCreateGameRule("doDaylightCycle", oldDaylight);
                world.getGameRules()
                    .setOrCreateGameRule("doMobSpawning", oldSpawning);
                world.setWorldTime(oldTime);
                if (active == this) active = null;
                report.addProperty("fixtureBedAndSupportsRetained", kind.equals("bed"));
                report.addProperty("complete", complete);
                report.addProperty("closed", true);
                capture("after-cleanup");
            }
            if (problem != null) {
                if (failure != null) failure.addSuppressed(problem);
                else throw new IllegalStateException("Exact native chunk cleanup failed", problem);
            }
        }
    }

    public static final class Cycle {

        final Scene s;
        final Entity before;
        final UUID id;
        final String label;
        final Chunk beforeChunk;
        final int chunkX, chunkZ;
        final MaidPolicyRuntime oldRuntime;
        final MaidPolicyContext.Token oldToken;
        NBTTagCompound savedEntity, savedLevel;
        Chunk loadedChunk;
        Entity reloaded;
        long unloadWorldTick = -1, frozenModern = -1, frozenLegacy = -1;
        boolean sawUnload, diskConfirmed, done, firstBedLeaseEmpty;
        int stage;

        Cycle(Scene s, Entity entity, String label, GameTestChunkLoader release) throws Exception {
            this.s = s;
            before = entity;
            id = entity.getUniqueID();
            this.label = label;
            chunkX = entity.chunkCoordX;
            chunkZ = entity.chunkCoordZ;
            beforeChunk = (Chunk) s.provider.loadedChunkHashMap
                .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
            require(
                beforeChunk != null && beforeChunk.isChunkLoaded && present(entity),
                "Actual registered source chunk required");
            oldRuntime = entity instanceof EntityMaid maid ? runtime(maid) : null;
            oldToken = oldRuntime == null ? null
                : oldRuntime.getContext()
                    .getToken();
            release.releaseAll();
            require(
                !s.world.levelSaving && !s.world.getPersistentChunks()
                    .containsKey(new ChunkCoordIntPair(chunkX, chunkZ)),
                "Source still forced/unsavable; do not bypass another ticket");
            require(
                !(Boolean) s.protectsChunk.invoke(s.scheduler, ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ)),
                "Actual scheduler still protects source; active-use window not eligible");
            s.cycles.add(this);
            s.provider.unloadChunksIfNotNearSpawn(chunkX, chunkZ); // Only enqueue; normal WorldServer.tick performs the
                                                                   // actual unload/save.
            s.event("native-unload-request-" + label, beforeChunk);
        }

        void checkRetired() {
            if (oldRuntime != null && frozenModern >= 0) require(
                oldRuntime.modernDispatchCount() == frozenModern && oldRuntime.legacyDispatchCount() == frozenLegacy
                    && !oldRuntime.getContext()
                        .isCurrent(oldToken),
                "Old unloaded runtime dispatched or regained its token");
        }

        void tick() throws Exception {
            checkRetired();
            if (stage == 0) {
                if (!sawUnload || savedEntity == null || s.provider.chunkExists(chunkX, chunkZ) || present(before))
                    return;
                require(!before.isDead, "Native source was killed instead of unloaded");
                if (oldRuntime != null) {
                    require(
                        oldRuntime.isRetired() && !oldRuntime.getContext()
                            .isCurrent(oldToken),
                        "Native ChunkEvent did not retire old policy/token");
                    frozenModern = oldRuntime.modernDispatchCount();
                    frozenLegacy = oldRuntime.legacyDispatchCount();
                    require(
                        ((EntityMaid) before).getItemUseState()
                            .peek() == null,
                        "Native unload retained shared item lease");
                }
                var loader = (AnvilChunkLoader) s.provider.currentChunkLoader;
                loader.saveExtraData();
                ThreadedFileIOBase.threadedIOInstance.waitForFinish();
                try (var input = RegionFileCache.getChunkInputStream(loader.chunkSaveLocation, chunkX, chunkZ)) {
                    require(input != null, "No physical native MCA data after actual unload");
                    var root = CompressedStreamTools.read(input);
                    require(root.hasKey("Level", 10), "Unknown native chunk disk structure");
                    var level = root.getCompoundTag("Level");
                    require(
                        level.getInteger("xPos") == chunkX && level.getInteger("zPos") == chunkZ,
                        "Physical native chunk coordinates differ");
                    var actual = find(level.getTagList("Entities", 10), id);
                    require(
                        savedEntity.equals(actual),
                        "Physical saved entity differs from native unload/save event snapshot");
                    require(
                        level.getTagList("TileEntities", 10)
                            .equals(savedLevel.getTagList("TileEntities", 10)),
                        "Physical native tile payload differs after unload/save");
                    var row = new JsonObject();
                    row.addProperty("cycle", label);
                    row.addProperty("worldTick", s.world.getTotalWorldTime());
                    row.add("nativeMcaEntity", binary(actual));
                    row.add("nativeSavedChunk", binary(level));
                    row.addProperty("actualDiskReadBeforeLoad", true);
                    s.events.add(row);
                }
                diskConfirmed = true;
                stage = 1;
                s.reloadedTickets
                    .forceChunksStrict(s.world, chunkX * 16, s.y, chunkZ * 16, chunkX * 16 + 15, s.y, chunkZ * 16 + 15);
                require(s.provider.chunkExists(chunkX, chunkZ), "Normal native chunk load did not register");
            }
            if (stage == 1) {
                if (reloaded == null || !present(reloaded)) return;
                require(
                    loadedChunk != null && loadedChunk != beforeChunk
                        && reloaded != before
                        && reloaded.getUniqueID()
                            .equals(id),
                    "No genuine native new chunk/entity reconstruction");
                if (reloaded instanceof EntityMaid next) {
                    var fresh = next.getBrainCoordinator()
                        .peekPolicyRuntime();
                    if (fresh == null || fresh.modernDispatchCount() == 0) return;
                    require(
                        fresh != oldRuntime && fresh.isReady()
                            && fresh.getProfile() == MaidBrainProfile.ENHANCED
                            && fresh.legacyDispatchCount() == 0,
                        "Rebuilt normal actor did not bind fresh complete Enhanced");
                }
                done = true;
            }
        }
    }

    private static final class ChangedBlock {

        final int x, y, z, beforeMeta, afterMeta;
        final Block before, after;

        ChangedBlock(int x, int y, int z, Block before, int beforeMeta, Block after, int afterMeta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.before = before;
            this.beforeMeta = beforeMeta;
            this.after = after;
            this.afterMeta = afterMeta;
        }
    }

    private static MaidPolicyRuntime runtime(EntityMaid maid) {
        var value = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        require(value != null && value.isReady(), "Naturally bound runtime required");
        return value;
    }

    private static boolean present(Entity entity) {
        if (entity == null || entity.isDead
            || entity.worldObj == null
            || entity.worldObj.getEntityByID(entity.getEntityId()) != entity) return false;
        for (Object value : entity.worldObj.loadedEntityList) if (value == entity) return true;
        return false;
    }

    private static NBTTagCompound nativeNbt(Entity entity) {
        var raw = new NBTTagCompound();
        entity.writeToNBT(raw);
        return (NBTTagCompound) raw.copy();
    }

    private static NBTTagCompound slots(EntityMaid maid) {
        var raw = new NBTTagCompound();
        require(
            maid.getMaidInventory()
                .getSizeInventory() == 12,
            "Actual twelve-slot interface required");
        for (int i = 0; i < maid.getMaidInventory()
            .getSizeInventory(); i++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(i);
            if (stack != null) raw.setTag("Slot" + i, ProtectedItemCodec.encode(stack));
        }
        return raw;
    }

    private static NBTTagCompound find(NBTTagList list, UUID id) {
        NBTTagCompound result = null;
        for (int i = 0; i < list.tagCount(); i++) {
            var value = list.getCompoundTagAt(i);
            if (value.hasKey("UUIDMost", 4) && value.hasKey("UUIDLeast", 4)
                && new UUID(value.getLong("UUIDMost"), value.getLong("UUIDLeast")).equals(id)) {
                require(result == null, "Duplicate actual saved UUID retained");
                result = (NBTTagCompound) value.copy();
            }
        }
        require(result != null, "Actual saved UUID missing");
        return result;
    }

    private static JsonObject binary(NBTTagCompound raw) {
        return SleepAttributePortSnapshot.binaryNbt(raw);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
