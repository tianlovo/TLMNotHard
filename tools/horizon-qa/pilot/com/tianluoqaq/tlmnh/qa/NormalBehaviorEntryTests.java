package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainCoordinator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

/** Startup config enters the production factory; no development selection or manual AI tick in the normal case. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NormalBehaviorEntryTests {

    private NormalBehaviorEntryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "normal_behavior_config")
    public static void normalServerConfigDrivesWildBoundAndNativeNbtInstances(GameTestHelper h) throws Exception {
        h.assertFalse(
            Boolean.getBoolean("tlmnh.devCommands"),
            "This entry must run in an independent non-development JVM");
        var scene = new Scene(h, "normal");
        h.afterTest(scene::close);
        try {
            scene.prepare();
        } catch (Exception | Error failure) {
            scene.failed(failure);
            throw failure;
        }
        var expected = MaidBehaviorConfig.profile();
        var wild = scene.fresh(0, false);
        var bound = scene.fresh(1, true);

        // A complete native pre-profile-format fixture, not a claim of reopening an arbitrary old player world.
        var seed = scene.unregistered(2, true);
        for (int slot = 0; slot < seed.getMaidInventory()
            .getSizeInventory(); slot++) {
            ItemStack stack;
            if (slot == 6) stack = new ItemStack(Items.iron_sword);
            else if (slot == 8) stack = new ItemStack(Items.iron_boots);
            else if (slot == 9) stack = new ItemStack(Items.iron_leggings);
            else if (slot == 10) stack = new ItemStack(Items.iron_chestplate);
            else if (slot == 11) stack = new ItemStack(Items.iron_helmet);
            else stack = new ItemStack(Items.diamond, slot + 1);
            var payload = new NBTTagCompound();
            payload.setString("UnknownItemPayload", "normal-entry-" + slot);
            payload.setIntArray("UnknownTypedArray", new int[] { slot, -17, 65537 });
            stack.setTagCompound(payload);
            seed.getMaidInventory()
                .setInventorySlotContents(slot, stack);
        }
        var heldRaw = new NBTTagCompound();
        heldRaw.setString("UnknownRecoveryPayload", "retain-the-original-raw");
        heldRaw.setLong("FutureFormat", 1099511627776L);
        seed.getMaidInventory()
            .recovery()
            .retain(heldRaw);
        var foreign = new NBTTagCompound();
        foreign.setString("Opaque", "keep-external-forge-data");
        seed.getEntityData()
            .setTag("NormalEntryForeign", foreign.copy());
        var inventory = scene.inventory(seed);
        var recovery = (net.minecraft.nbt.NBTTagList) seed.getMaidInventory()
            .recovery()
            .write()
            .copy();
        var saved = Scene.nativeNbt(seed);
        var extension = saved.getCompoundTag("TLMNH");
        extension.setTag("UnknownBehaviorExtension", foreign.copy());
        saved.setTag("TLMNH", extension);
        scene.report.add("oldFormatNativeInput", SleepAttributePortSnapshot.binaryNbt(saved));
        var restored = new EntityMaid(scene.world);
        restored.readFromNBT((NBTTagCompound) saved.copy());
        h.assertTrue(
            inventory.equals(scene.inventory(restored)) && recovery.equals(
                restored.getMaidInventory()
                    .recovery()
                    .write()),
            "New native instance lost complete pre-save12-slot or recovery payload");
        h.assertTrue(
            restored.getBrainCoordinator()
                .peekPolicyRuntime() == null,
            "Native read activated policy before complete registration");
        if (expected == MaidBrainProfile.ENHANCED) h.assertTrue(
            restored.getBrainCoordinator()
                .getPolicyRuntime() == null,
            "Unregistered native reload activated complete N");
        scene.join(restored);
        seed.setDead();
        h.assertTrue(
            inventory.equals(scene.inventory(restored)) && recovery.equals(
                restored.getMaidInventory()
                    .recovery()
                    .write()),
            "Native join changed original complete material payload");
        int[] age = { 0 };
        h.onEachTick("ordinary startup behavior entries", () -> {
            try {
                if (!scene.naturallyTicked()) return;
                age[0]++;
                scene.frame(expected);
                h.assertTrue(
                    inventory.equals(scene.inventory(restored)) && recovery.equals(
                        restored.getMaidInventory()
                            .recovery()
                            .write()),
                    "Normal AI changed full native inventory/recovery payload");
                var actual = Scene.nativeNbt(restored);
                h.assertTrue(
                    foreign.equals(
                        actual.getCompoundTag("TLMNH")
                            .getCompoundTag("UnknownBehaviorExtension"))
                        && foreign.equals(
                            restored.getEntityData()
                                .getCompoundTag("NormalEntryForeign")),
                    "Normal AI binding lost unknown owned-extension or external native ForgeData");
                if (age[0] == 8) {
                    h.assertFalse(wild.isTamed(), "Wild entry was implicitly tamed by behavior selection");
                    h.assertTrue(wild.bindTo(scene.owner), "Normal owner binding refused");
                }
                if (age[0] == 12) {
                    var current = bound.getBrainCoordinator()
                        .peekPolicyRuntime();
                    for (var forbidden : new MaidBrainProfile[] { MaidBrainProfile.FAITHFUL_TLM,
                        MaidBrainProfile.FOLLOW_CORE_COMPONENT, MaidBrainProfile.ENHANCED_CORE_COMPONENT })
                        h.assertTrue(
                            bound.selectBrainProfileForDevelopment(forbidden)
                                == MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE,
                            "Non-development JVM admitted a research selection");
                    var component = new CountingActions();
                    boolean rejected = false;
                    try {
                        bound.getBrainCoordinator()
                            .setAfterNavigationActionsForDevelopment(component);
                    } catch (IllegalStateException expectedRefusal) {
                        rejected = true;
                    }
                    h.assertTrue(
                        rejected && component.ticks == 0
                            && current == bound.getBrainCoordinator()
                                .peekPolicyRuntime(),
                        "Research callback changed a normal policy");
                }
                if (age[0] == 28) {
                    h.assertTrue(
                        wild.isTamed() && bound.isTamed() && restored.isTamed(),
                        "Owner state changed across normal entries");
                    scene.report.add("nativeOutput", SleepAttributePortSnapshot.binaryNbt(actual));
                    scene.complete = true;
                }
            } catch (RuntimeException | Error failure) {
                scene.failed(failure);
                throw failure;
            }
        });
        h.succeedWhen(() -> scene.complete);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "normal_behavior_dev_override")
    public static void explicitDevelopmentLegacyAndComponentsOverrideConfiguredEnhanced(GameTestHelper h)
        throws Exception {
        h.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
            "Separate development JVM with actual enhanced startup config required");
        var scene = new Scene(h, "development-overrides");
        h.afterTest(scene::close);
        try {
            scene.prepare();
        } catch (Exception | Error failure) {
            scene.failed(failure);
            throw failure;
        }
        var maid = scene.fresh(0, true);
        var component = new CountingActions();
        int[] age = { 0 };
        h.onEachTick("explicit dev selection takes precedence over normal config", () -> {
            try {
                if (!scene.naturallyTicked()) return;
                int now = ++age[0];
                var brain = maid.getBrainCoordinator();
                if (now < 6) scene.assertProfile(maid, MaidBrainProfile.ENHANCED);
                if (now == 6) h.assertTrue(
                    maid.selectBrainProfileForDevelopment(MaidBrainProfile.LEGACY_PROTOTYPE)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Explicit development Legacy refused");
                if (now > 6 && now < 15) scene.assertProfile(maid, MaidBrainProfile.LEGACY_PROTOTYPE);
                if (now == 10) maid.readFromNBT(Scene.nativeNbt(maid));
                if (now == 15) brain.setAfterNavigationActionsForDevelopment(component);
                if (now > 15 && now < 22) {
                    scene.assertProfile(maid, MaidBrainProfile.FOLLOW_CORE_COMPONENT);
                    h.assertTrue(
                        component.ticks > 0 && brain.peekEnhancedCore() == null,
                        "Config replaced explicit narrow component with full N");
                }
                if (now == 22) h.assertTrue(
                    maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Development F reference refused");
                if (now > 22 && now < 34) scene.assertProfile(maid, MaidBrainProfile.FAITHFUL_TLM);
                if (now == 27) maid.readFromNBT(Scene.nativeNbt(maid));
                if (now == 34) h.assertTrue(
                    maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Explicit complete N refused");
                if (now > 34) scene.assertProfile(maid, MaidBrainProfile.ENHANCED);
                scene.record(maid);
                if (now == 40) scene.complete = true;
            } catch (RuntimeException | Error failure) {
                scene.failed(failure);
                throw failure;
            }
        });
        h.succeedWhen(() -> scene.complete);
    }

    private static final class CountingActions implements MaidPolicyRuntime.AfterNavigationActions {

        int ticks;

        public void tick(MaidPolicyContext.Token token) {
            ticks++;
        }

        public void cancel() {}
    }

    /** Read-only packaged identity; normal-entry evidence must not require or toggle a development permission. */
    private static JsonObject artifactIdentity(Class<?>... types) throws Exception {
        if (types.length == 0) throw new IllegalArgumentException("Packaged identity classes required");
        java.nio.file.Path artifact = null;
        var classes = new JsonObject();
        var bindings = new JsonObject();
        for (var type : types) {
            var file = PackagedClassResourceResolver.artifactOf(type);
            if (artifact == null) artifact = file;
            if (!artifact.equals(file)) throw new IllegalStateException("Mixed packaged identity artifacts");
            var binding = PackagedClassResourceResolver.resolve(type, artifact);
            classes.addProperty(type.getName(), binding.sha256);
            var detail = new JsonObject();
            detail.addProperty("logicalEntry", binding.logicalEntry);
            detail.addProperty("physicalEntry", binding.physicalEntry);
            detail.addProperty("multiReleaseVersion", binding.multiReleaseVersion);
            bindings.add(type.getName(), detail);
        }
        var digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(artifact))) {
            digest.append(Character.forDigit((value & 255) >>> 4, 16));
            digest.append(Character.forDigit(value & 15, 16));
        }
        var result = new JsonObject();
        result.addProperty(
            "artifactFile",
            artifact.getFileName()
                .toString());
        result.addProperty("artifactSha256", digest.toString());
        result.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        result.addProperty("transformedDefinitionBytesVerified", false);
        result.add("classResources", classes);
        result.add("classResourceBindings", bindings);
        return result;
    }

    private static final class Scene {

        final GameTestHelper h;
        final WorldServer world;
        final MinecraftServer server = MinecraftServer.getServer();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final List<EntityMaid> actors = new ArrayList<>();
        final Map<EntityMaid, NBTTagCompound> ownedInventory = new IdentityHashMap<>();
        final Map<EntityMaid, net.minecraft.nbt.NBTTagList> ownedRecovery = new IdentityHashMap<>();
        final List<int[]> floor = new ArrayList<>();
        final UUID owner = UUID.randomUUID();
        final String run = UUID.randomUUID()
            .toString(), name;
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray();
        final boolean animals;
        final long time;
        final int x, y, z;
        boolean complete;

        Scene(GameTestHelper h, String name) throws Exception {
            this.h = h;
            this.name = name;
            world = h.getWorld();
            var origin = h.absolute(0, 0, 0);
            x = origin.x() + 64;
            y = origin.y();
            z = origin.z() + 64;
            animals = server.getCanSpawnAnimals();
            time = world.getWorldTime();
            report.addProperty("schema", "normal-behavior-entry-v1");
            report.addProperty("case", name);
            report.addProperty("developmentCommands", Boolean.getBoolean("tlmnh.devCommands"));
            report.addProperty("configuredValue", MaidBehaviorConfig.configuredValue());
            report.addProperty(
                "resolvedProfile",
                MaidBehaviorConfig.profile()
                    .getId());
            report.addProperty("fallbackUsed", MaidBehaviorConfig.usedFallback());
            report.addProperty(
                "scope",
                "Native registered wild/bound and new native-NBT instances; fixture offline owner UUID, not a real multiplayer claim. No per-maid profile field or schema migration");
            report.add(
                "productionArtifact",
                artifactIdentity(
                    MaidBehaviorConfig.class,
                    MaidBrainCoordinator.class,
                    MaidBrainProfile.class,
                    MaidPolicyRuntime.class,
                    EntityMaid.class));
            report.add(
                "observerArtifact",
                artifactIdentity(NormalBehaviorEntryTests.class, Scene.class, CountingActions.class));
        }

        void prepare() throws Exception {
            server.setCanSpawnAnimals(true);
            world.setWorldTime(6000);
            chunks.forceChunksStrict(world, x - 16, y, z - 16, x + 47, y + 6, z + 47);
            for (int bx = x; bx < x + 32; bx++) for (int bz = z; bz < z + 32; bz++) {
                for (int by = y; by < y + 4; by++) h.assertTrue(
                    world.isAirBlock(bx, by, bz) && world.getTileEntity(bx, by, bz) == null,
                    "Unknown normal-entry course contents retained");
                h.assertTrue(
                    world.setBlock(bx, y, bz, Blocks.iron_block, 0, 2),
                    "Native normal-entry floor placement failed");
                floor.add(new int[] { bx, y, bz });
            }
        }

        EntityMaid unregistered(int index, boolean bound) {
            var maid = new EntityMaid(world);
            maid.setPosition(x + 6.5 + index * 7, y + 1, z + 12.5);
            if (bound) h.assertTrue(maid.bindTo(owner), "Fixture owner binding failed");
            maid.setTask(MaidTask.IDLE);
            maid.setSchedule(MaidSchedule.ALL);
            maid.setPickup(false);
            maid.setHunger(100);
            maid.getEntityData()
                .setString("NormalBehaviorRun", run);
            h.assertTrue(
                maid.getBrainCoordinator()
                    .peekPolicyRuntime() == null,
                "Entity construction eagerly activated a policy");
            return maid;
        }

        EntityMaid fresh(int index, boolean bound) {
            var maid = unregistered(index, bound);
            join(maid);
            return maid;
        }

        void join(EntityMaid maid) {
            actors.add(maid);
            ownedInventory.put(maid, inventory(maid));
            ownedRecovery.put(
                maid,
                (net.minecraft.nbt.NBTTagList) maid.getMaidInventory()
                    .recovery()
                    .write()
                    .copy());
            h.assertTrue(
                world.spawnEntityInWorld(maid) && MaidSpawnChecks.present(maid),
                "Actual native registration failed");
            h.assertTrue(
                ownedInventory.get(maid)
                    .equals(inventory(maid))
                    && ownedRecovery.get(maid)
                        .equals(
                            maid.getMaidInventory()
                                .recovery()
                                .write()),
                "Native join changed known fixture material; successor retained");
        }

        boolean naturallyTicked() {
            for (var maid : actors) if (maid.ticksExisted < 1) return false;
            return true;
        }

        void frame(MaidBrainProfile profile) {
            for (var maid : actors) {
                assertProfile(maid, profile);
                record(maid);
            }
        }

        void assertProfile(EntityMaid maid, MaidBrainProfile expected) {
            h.assertTrue(MaidSpawnChecks.present(maid) && maid.isEntityAlive(), "Normal entry actor unloaded or died");
            var brain = maid.getBrainCoordinator();
            var runtime = brain.peekPolicyRuntime();
            h.assertTrue(
                runtime != null && runtime.isReady() && runtime.getProfile() == expected,
                "Actual naturally bound strategy differs from selected startup/explicit profile");
            if (expected == MaidBrainProfile.ENHANCED) h.assertTrue(
                brain.peekEnhancedCore() != null && brain.peekEnhancedCore()
                    .decisions() > 0
                    && runtime.modernDispatchCount() > 0
                    && runtime.legacyDispatchCount() == 0
                    && brain.peekEnhancedCore()
                        .getFlightProfile() == ModernFlightProfile.NATIVE_MOTION_COMPENSATION,
                "Normal complete N did not naturally run its native-motion root or entered Legacy");
            else if (expected == MaidBrainProfile.LEGACY_PROTOTYPE) h.assertTrue(
                brain.peekEnhancedCore() == null && runtime.legacyDispatchCount() > 0
                    && runtime.modernDispatchCount() == 0,
                "Actual Legacy branch did not run exclusively");
            else {
                h.assertTrue(
                    runtime.modernDispatchCount() > 0 && runtime.legacyDispatchCount() == 0,
                    "Explicit research mode entered Legacy actions");
                if (expected == MaidBrainProfile.FAITHFUL_TLM) h.assertTrue(
                    brain.peekFaithfulBrain() != null && brain.peekFaithfulBrain()
                        .getFlightProfile()
                        == com.tianluoqaq.tlmnh.common.ai.faithful.combat.MaidFaithfulFlightProfile.SOURCE_ORIGINAL,
                    "Development F reference was replaced by production N/native physics");
                else h.assertTrue(
                    brain.peekFaithfulBrain() == null && brain.peekEnhancedCore() == null,
                    "Narrow component was promoted to a complete root");
            }
        }

        void record(EntityMaid maid) {
            var row = new JsonObject();
            var runtime = maid.getBrainCoordinator()
                .peekPolicyRuntime();
            row.addProperty("worldTick", world.getTotalWorldTime());
            row.addProperty(
                "uuid",
                maid.getUniqueID()
                    .toString());
            row.addProperty("tamed", maid.isTamed());
            row.addProperty("nativeAge", maid.ticksExisted);
            row.addProperty(
                "profile",
                runtime == null ? "UNBOUND"
                    : runtime.getProfile()
                        .getId());
            row.addProperty("legacyDispatches", runtime == null ? 0 : runtime.legacyDispatchCount());
            row.addProperty("modernDispatches", runtime == null ? 0 : runtime.modernDispatchCount());
            frames.add(row);
        }

        NBTTagCompound inventory(EntityMaid maid) {
            var out = new NBTTagCompound();
            for (int i = 0; i < maid.getMaidInventory()
                .getSizeInventory(); i++) {
                var stack = maid.getMaidInventory()
                    .getStackInSlot(i);
                if (stack != null) out.setTag("Slot" + i, ProtectedItemCodec.encode(stack));
            }
            return (NBTTagCompound) out.copy();
        }

        static NBTTagCompound nativeNbt(EntityMaid maid) {
            var raw = new NBTTagCompound();
            maid.writeToNBT(raw);
            return (NBTTagCompound) raw.copy();
        }

        void failed(Throwable failure) {
            if (!report.has("primaryFailure")) {
                var trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                report.addProperty("primaryFailure", trace.toString());
            }
            try {
                save();
            } catch (RuntimeException saveFailure) {
                failure.addSuppressed(saveFailure);
            }
        }

        void save() {
            report.add("frames", frames);
            report.addProperty("completed", complete);
            try {
                Files.write(
                    Paths.get(System.getProperty("horizonqa.reportDir"), "normal-behavior-" + name + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain actual normal behavior evidence", failure);
            }
        }

        void close() {
            Throwable first = null;
            for (var maid : new ArrayList<>(actors)) try {
                h.assertTrue(
                    maid.worldObj == world && run.equals(
                        maid.getEntityData()
                            .getString("NormalBehaviorRun")),
                    "Unknown normal-entry actor successor retained");
                h.assertTrue(
                    ownedInventory.containsKey(maid) && ownedInventory.get(maid)
                        .equals(inventory(maid))
                        && ownedRecovery.get(maid)
                            .equals(
                                maid.getMaidInventory()
                                    .recovery()
                                    .write()),
                    "Unknown normal-entry material successor retained");
                h.assertTrue(MaidSpawnChecks.present(maid), "Unknown normal-entry registration successor retained");
                maid.getBrainCoordinator()
                    .setSuspended(true);
                world.removePlayerEntityDangerously(maid);
                maid.setDead();
                actors.remove(maid);
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
            if (actors.isEmpty()) for (var pos : floor) {
                if (world.getBlock(pos[0], pos[1], pos[2]) != Blocks.iron_block
                    || world.getBlockMetadata(pos[0], pos[1], pos[2]) != 0
                    || world.getTileEntity(pos[0], pos[1], pos[2]) != null) {
                    if (first == null) first = new IllegalStateException("Unknown normal-entry floor retained");
                    continue;
                }
                world.setBlock(pos[0], pos[1], pos[2], Blocks.air, 0, 2);
            }
            chunks.releaseAll();
            // In this single-selector child a retained unknown actor must not be killed by CI's Animal=false setting.
            server.setCanSpawnAnimals(actors.isEmpty() ? animals : true);
            world.setWorldTime(time);
            report.addProperty("exactOwnedCleanup", first == null && actors.isEmpty());
            if (first != null) report.addProperty("cleanupFailure", first.toString());
            save();
            if (first != null)
                throw new IllegalStateException("Normal-entry cleanup retained unknown successor", first);
        }
    }
}
