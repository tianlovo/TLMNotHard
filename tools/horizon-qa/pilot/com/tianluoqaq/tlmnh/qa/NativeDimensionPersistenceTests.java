package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidDimensionTravel;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Normal native dimension moves and ordinary stopped/restarted saves; no crash or custom migration ledger. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NativeDimensionPersistenceTests {

    private static final String MARKER = "TLMNHDimension204Run";
    private static final String INPUT = "tlmnh-dimension-restart.nbt";
    private static final String[] PAYLOAD = { "OwnerUUID", "ModelId", "SoundPackId", "MaidInventory", "Equipment",
        "TLMNHOffhand", "TLMNHRecovery", "MaidExperience", "MaidFavorability", "MaidHunger", "TLMNH", "ForgeData",
        "MaidSubConfig", "MaidTaskDataMaps", "TLMNHHome", "MaidTask", "MaidScheduleMode", "Sitting" };

    private NativeDimensionPersistenceTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "native_dimension_setup")
    public static void setupInitialTravelAndSafeCancellation(GameTestHelper helper) throws Exception {
        var trial = new Trial(helper, "setup");
        try {
            trial.setup();
            helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> trial.complete(false));
        } catch (Exception | Error failure) {
            trial.failure(failure);
            throw failure;
        }
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "native_dimension_resume")
    public static void resumeSavedActorsAndReturnThroughNativeEndExit(GameTestHelper helper) throws Exception {
        var trial = new Trial(helper, "resume");
        try {
            trial.resume();
            helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> trial.complete(true));
        } catch (Exception | Error failure) {
            trial.failure(failure);
            throw failure;
        }
    }

    public static final class Trial {

        final GameTestHelper helper;
        final String phase;
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final JsonObject report = new JsonObject();
        final Map<String, EntityMaid> actors = new LinkedHashMap<>();
        final Map<String, NBTTagCompound> before = new LinkedHashMap<>();
        final Map<UUID, NBTTagCompound> expectedLoads = new LinkedHashMap<>();
        final WorldServer source, end;
        final String artifact;
        String run;
        EntityMaid requestedSource, observedCandidate;
        boolean cancelSpawn, closed;
        int callbacks;
        Throwable observerFailure;

        Trial(GameTestHelper helper, String phase) throws Exception {
            this.helper = helper;
            this.phase = phase;
            helper.assertTrue(
                Boolean.getBoolean("tlmnh.devCommands") && phase.equals(System.getProperty("tlmnh.qaDimensionPhase")),
                "Explicit dimension stage required");
            helper.assertTrue(
                "normal".equals(System.getProperty("horizonqa.world"))
                    && "interactive".equals(System.getProperty("horizonqa.mode"))
                    && "1".equals(System.getProperty("horizonqa.turbo")),
                "Actual normal native server policy/turbo1 required");
            helper.assertTrue(
                MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
                "Normal startup Enhanced config required");
            source = helper.getWorld();
            helper.assertTrue(source.provider.dimensionId == 0, "Actual native overworld required");
            report.addProperty("schema", "native-dimension-persistence-v1");
            report.addProperty("phase", phase);
            report.addProperty("arbitraryCrashAtomicityClaimed", false);
            report.addProperty("actualNetworkPlayersClaimed", false);
            report.addProperty(
                "originalCanSpawnAnimals",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            // This sole persistent fixture JVM keeps native animals enabled through the ordinary shutdown save.
            MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
            helper.afterTest(this::close);
            var production = DevelopmentArtifactIdentity.capture(EntityMaid.class, MaidDimensionTravel.class);
            artifact = production.get("artifactSha256")
                .getAsString();
            report.add("productionArtifact", production);
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(NativeDimensionPersistenceTests.class, Trial.class));
            end = MinecraftServer.getServer()
                .worldServerForDimension(1);
            helper.assertTrue(end != null && end.provider.dimensionId == 1, "Actual native End world required");
            report.addProperty(
                "sourceProvider",
                source.getChunkProvider()
                    .makeString());
            report.addProperty(
                "destinationProvider",
                end.getChunkProvider()
                    .makeString());
            report.addProperty(
                "actualTeleporter",
                end.getDefaultTeleporter()
                    .getClass()
                    .getName());
            MinecraftForge.EVENT_BUS.register(this);
        }

        void force(WorldServer world, int x, int z) throws Exception {
            chunks.forceChunksStrict(world, x - 16, 0, z - 16, x + 16, 255, z + 16);
        }

        void setup() throws Exception {
            run = UUID.randomUUID()
                .toString();
            report.addProperty("run", run);
            var entrance = end.getEntrancePortalLocation();
            helper.assertTrue(entrance != null, "Native End entrance missing");
            force(end, entrance.posX, entrance.posZ);
            var forward = seed(1282, "forward");
            var retained = seed(1314, "canceled");
            var forwardBefore = snapshot(forward);
            before.put("forward", forwardBefore);
            travel(forward, 1, false);
            helper.assertTrue(
                forward.isDead && observedCandidate != null
                    && present(observedCandidate, end)
                    && liveCopies(forward.getUniqueID()) == 1,
                "Forward native move did not preserve a single identity");
            helper.assertTrue(
                nonPlacement(forwardBefore).equals(nonPlacement(snapshot(observedCandidate))),
                "Immediate full non-placement native transfer payload changed");
            actors.put("forward", observedCandidate);
            var retainedBefore = snapshot(retained);
            before.put("canceled", retainedBefore);
            travel(retained, 1, true);
            helper.assertTrue(
                present(retained, source) && retainedBefore.equals(snapshot(retained))
                    && observedCandidate != null
                    && observedCandidate.isDead
                    && liveCopies(retained.getUniqueID()) == 1,
                "Canceled target spawn changed the original or retained a second actor");
            actors.put("canceled", retained);
        }

        EntityMaid seed(int x, String key) throws Exception {
            force(source, x, 1282);
            for (int a = x - 2; a <= x + 2; a++) for (int b = 1280; b <= 1284; b++) {
                helper.assertTrue(
                    source.isAirBlock(a, 191, b) && source.getTileEntity(a, 191, b) == null,
                    "Original empty high platform required; no unknown native block is replaced");
                helper.assertTrue(
                    source.setBlock(a, 191, b, Blocks.bedrock, 0, 2),
                    "Finite collision platform placement failed");
            }
            var maid = new EntityMaid(source);
            var identity = maid.getUniqueID();
            helper.assertTrue(maid.bindTo(UUID.randomUUID()), "Native seed owner bind failed");
            maid.setSitting(true);
            maid.setPickup(false);
            maid.func_110163_bv();
            maid.setExperience(37);
            maid.setModelId("geckolib:winefox_hanfu");
            maid.getBrainCoordinator()
                .setSuspended(true);
            for (int slot = 0; slot < maid.getMaidInventory()
                .getSizeInventory(); slot++) {
                var stack = new ItemStack(Items.diamond, slot + 1, slot);
                stack.setTagCompound(new NBTTagCompound());
                stack.getTagCompound()
                    .setString("TravelIdentity", identity.toString());
                stack.getTagCompound()
                    .setIntArray("Opaque", new int[] { slot, 17, 31 });
                maid.getMaidInventory()
                    .setInventorySlotContents(slot, stack);
            }
            var raw = new NBTTagCompound();
            raw.setString("id", "unavailable:dimension204-original");
            raw.setInteger("OpaqueRecovery", 73);
            maid.getMaidInventory()
                .recovery()
                .retain(raw);
            maid.getEntityData()
                .setString(MARKER, run);
            var extra = new NBTTagCompound();
            extra.setByteArray("OpaqueBytes", new byte[] { 0, -1, 3 });
            extra.setDouble("ExactDouble", -.0D);
            maid.getEntityData()
                .setTag("Dimension204Opaque", extra);
            var nativeBefore = snapshot(maid);
            nativeBefore.getCompoundTag("TLMNH")
                .setIntArray("DimensionOpaque", new int[] { 11, 17, 23 });
            maid.readFromNBT(nativeBefore);
            maid.setLocationAndAngles(x + .5, 192, 1282.5, 0, 0);
            helper.assertTrue(
                source.spawnEntityInWorld(maid) && present(maid, source),
                "Native source registration failed");
            report.addProperty(key + "Owner", maid.func_152113_b());
            return maid;
        }

        void resume() throws Exception {
            var path = Paths.get(INPUT);
            helper.assertTrue(
                Files.isRegularFile(path) && Files.size(path) <= 8 * 1024 * 1024,
                "Bound next-process fixture input missing");
            NBTTagCompound input;
            try (var stream = new DataInputStream(Files.newInputStream(path))) {
                input = CompressedStreamTools.read(stream);
            }
            helper.assertTrue(
                input.getInteger("Schema") == 1 && artifact.equals(input.getString("MainSha256")),
                "Same frozen candidate input required");
            run = input.getString("Run");
            UUID.fromString(run);
            report.addProperty("run", run);
            var rows = input.getTagList("Actors", 10);
            helper.assertTrue(rows.tagCount() == 2, "Exactly two original actor checkpoints required");
            for (int i = 0; i < rows.tagCount(); i++) {
                var row = rows.getCompoundTagAt(i);
                String key = row.getString("Key");
                helper.assertTrue(
                    (key.equals("forward") || key.equals("canceled")) && !before.containsKey(key),
                    "Unknown duplicate actor role");
                var expected = row.getCompoundTag("Snapshot");
                UUID id = UUID.fromString(row.getString("Id"));
                helper.assertTrue(
                    id.equals(uuid(expected)) && run.equals(
                        expected.getCompoundTag("ForgeData")
                            .getString(MARKER)),
                    "Exact native source identity required");
                before.put(key, (NBTTagCompound) expected.copy());
                expectedLoads.put(id, expected);
                int dimension = row.getInteger("Dimension");
                helper.assertTrue(dimension == (key.equals("forward") ? 1 : 0), "Original saved dimension changed");
                var world = dimension == 1 ? end : source;
                var positions = expected.getTagList("Pos", 6);
                helper.assertTrue(positions.tagCount() == 3, "Native position required to load the original chunk");
                force(
                    world,
                    (int) Math.floor(positions.func_150309_d(0)),
                    (int) Math.floor(positions.func_150309_d(2)));
                var loaded = find(world, id);
                helper.assertTrue(
                    loaded != null && liveCopies(id) == 1 && present(loaded, world),
                    "Original saved actor did not load uniquely");
                loaded.getBrainCoordinator()
                    .setSuspended(true);
                assertPayload(snapshot(loaded), expected, key + " native reload");
                actors.put(key, loaded);
            }
            helper.assertTrue(observerFailure == null, "Original entity-load observer failed: " + observerFailure);
            var forward = actors.get("forward");
            var old = snapshot(forward);
            travel(forward, 1, false); // Native End->End request is the original End-exit branch, targeting overworld0.
            helper.assertTrue(
                forward.isDead && observedCandidate != null
                    && present(observedCandidate, source)
                    && liveCopies(forward.getUniqueID()) == 1,
                "Native End exit failed to retire only the End source");
            helper.assertTrue(
                nonPlacement(old).equals(nonPlacement(snapshot(observedCandidate))),
                "Immediate native return changed full non-placement NBT");
            actors.put("forward", observedCandidate);
        }

        void travel(EntityMaid maid, int dimension, boolean canceled) {
            requestedSource = maid;
            observedCandidate = null;
            callbacks = 0;
            cancelSpawn = canceled;
            maid.travelToDimension(dimension);
            helper.assertTrue(
                callbacks == 1 && observedCandidate != null && observerFailure == null,
                "Exactly one real native target join expected");
            requestedSource = null;
            cancelSpawn = false;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void joining(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityMaid maid)) return;
            try {
                var expected = expectedLoads.get(maid.getUniqueID());
                if (expected != null && run.equals(
                    maid.getEntityData()
                        .getString(MARKER))) {
                    assertPayload(snapshot(maid), expected, "earliest native entity join");
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                }
                if (requestedSource == null || event.world == requestedSource.worldObj
                    || !requestedSource.getUniqueID()
                        .equals(maid.getUniqueID())
                    || !run.equals(
                        maid.getEntityData()
                            .getString(MARKER)))
                    return;
                callbacks++;
                observedCandidate = maid;
                maid.getBrainCoordinator()
                    .setSuspended(true);
                if (cancelSpawn) event.setCanceled(true);
            } catch (RuntimeException | Error failure) {
                if (observerFailure == null) observerFailure = failure;
            }
        }

        void complete(boolean returned) {
            try {
                helper.assertTrue(observerFailure == null, "Native observer failed: " + observerFailure);
                var rows = new JsonArray();
                for (var entry : actors.entrySet()) {
                    var key = entry.getKey();
                    var maid = entry.getValue();
                    helper.assertTrue(
                        present(maid, maid.worldObj) && liveCopies(maid.getUniqueID()) == 1
                            && run.equals(
                                maid.getEntityData()
                                    .getString(MARKER)),
                        "Original identity lost before normal save");
                    helper.assertTrue(
                        maid.dimension == (returned || key.equals("canceled") ? 0 : 1),
                        "Wrong native final dimension");
                    var actual = snapshot(maid);
                    assertPayload(actual, before.get(key), "final " + key);
                    var row = new JsonObject();
                    row.addProperty("key", key);
                    row.addProperty(
                        "actor",
                        maid.getUniqueID()
                            .toString());
                    row.addProperty("dimension", maid.dimension);
                    row.addProperty("owner", maid.func_152113_b());
                    row.addProperty("beforeNativeNbt", binary(before.get(key)));
                    row.addProperty("afterNativeNbt", binary(actual));
                    row.addProperty("liveCopies", liveCopies(maid.getUniqueID()));
                    rows.add(row);
                    var runtime = maid.getBrainCoordinator()
                        .getPolicyRuntime();
                    helper.assertTrue(
                        runtime != null && runtime.getProfile() == MaidBrainProfile.ENHANCED
                            && runtime.legacyDispatchCount() == 0,
                        "Native reload must resolve normal Enhanced without dev selection");
                }
                report.add("actors", rows);
                report.addProperty("complete", true);
                write();
                helper.succeed();
            } catch (Exception | Error failure) {
                failure(failure);
                throw new IllegalStateException("Native dimension persistence observation failed", failure);
            }
        }

        void failure(Throwable failure) {
            if (!report.has("failure")) report.addProperty("failure", failure.toString());
            try {
                write();
            } catch (Exception logging) {
                failure.addSuppressed(logging);
            }
        }

        void write() throws Exception {
            Files.write(
                Paths.get(System.getProperty("horizonqa.reportDir"))
                    .resolve("native-dimension-" + phase + ".json"),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        }

        void close() {
            if (closed) return;
            closed = true;
            Throwable cleanupFailure = null;
            try {
                MinecraftForge.EVENT_BUS.unregister(this);
                chunks.releaseAll();
                report.addProperty("closed", true);
            } catch (RuntimeException | Error cleanup) {
                cleanupFailure = cleanup;
                report.addProperty("cleanupFailure", cleanup.toString());
                throw cleanup;
            } finally {
                // Keep the known persistent actors/floors and enabled animals through the native shutdown save.
                report.addProperty(
                    "finalCanSpawnAnimals",
                    MinecraftServer.getServer()
                        .getCanSpawnAnimals());
                report.addProperty("actorsIntentionallyRetainedForNativeSave", true);
                try {
                    write();
                } catch (Exception logging) {
                    if (cleanupFailure != null) cleanupFailure.addSuppressed(logging);
                    else throw new IllegalStateException(logging);
                }
            }
        }
    }

    private static void assertPayload(NBTTagCompound actual, NBTTagCompound expected, String label) {
        for (String key : PAYLOAD) if (!java.util.Objects.equals(actual.getTag(key), expected.getTag(key)))
            throw new IllegalStateException(label + " changed full protected field " + key);
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var tag = new NBTTagCompound();
        tag.setString("id", EntityList.getEntityString(maid));
        maid.writeToNBT(tag);
        return (NBTTagCompound) tag.copy();
    }

    private static String binary(NBTTagCompound tag) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
        }
        return Base64.getEncoder()
            .encodeToString(bytes.toByteArray());
    }

    private static NBTTagCompound nonPlacement(NBTTagCompound full) {
        var tag = (NBTTagCompound) full.copy();
        for (String key : new String[] { "Pos", "Motion", "Rotation", "Dimension" }) tag.removeTag(key);
        return tag;
    }

    private static UUID uuid(NBTTagCompound tag) {
        return new UUID(tag.getLong("UUIDMost"), tag.getLong("UUIDLeast"));
    }

    private static boolean present(EntityMaid maid, net.minecraft.world.World world) {
        if (!maid.isEntityAlive() || maid.worldObj != world || world.getEntityByID(maid.getEntityId()) != maid)
            return false;
        for (Object value : world.loadedEntityList) if (value == maid) return true;
        return false;
    }

    private static EntityMaid find(WorldServer world, UUID id) {
        EntityMaid found = null;
        for (Object value : world.loadedEntityList)
            if (value instanceof EntityMaid maid && id.equals(maid.getUniqueID())) {
                if (found != null) throw new IllegalStateException("Duplicate loaded original actor");
                found = maid;
            }
        return found;
    }

    private static int liveCopies(UUID id) {
        int count = 0;
        for (var world : DimensionManager.getWorlds()) for (Object value : world.loadedEntityList)
            if (value instanceof EntityMaid maid && maid.isEntityAlive() && id.equals(maid.getUniqueID())) count++;
        return count;
    }
}
