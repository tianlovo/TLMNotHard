package com.tianluoqaq.tlmnh.common.development;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.Tags;
import com.tianluoqaq.tlmnh.common.ai.faithful.DryPlatformFollowTeleport;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

/** 原版实际随机流驱动原生平台真实碰撞与传送，不执行Brain、导航sink或自然实体tick */
public final class MaidFollowTeleportProbe {

    private MaidFollowTeleportProbe() {}

    public static void run(WorldServer world, String name) {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && !world.isRemote && world.provider.dimensionId == 0,
            "Dedicated development overworld required");
        require(name.matches("[0-9a-f]{64}\\.json"), "Input name must be its SHA256 plus json extension");
        JsonObject output = new JsonObject();
        output.addProperty("schema", "follow-teleport-port-native-v1");
        output.addProperty("version", Tags.VERSION);
        output.addProperty(
            "scope",
            "unregistered native participants, real native STONE/air collision and movement with original recorded RNG; no Brain, online-owner lookup or memory-layer equivalence");
        output.addProperty("equivalenceVerified", false);
        output.addProperty("naturalBehaviourVerified", false);
        output.addProperty("memoryLayerVerified", false);
        var rows = new JsonArray();
        output.add("cases", rows);
        var checks = new JsonArray();
        output.add("guardChecks", checks);
        Throwable failure = null;
        try {
            output.add("runtimeIdentity", runtimeIdentity());
            Path input = checkedDirectory(Paths.get("tlmnh-dev", "follow-inputs"), false).resolve(name);
            require(
                !Files.isSymbolicLink(input) && Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS),
                "Regular input required");
            require(Files.size(input) <= 8_388_608L, "Follow input exceeds bounded capture size");
            byte[] bytes = Files.readAllBytes(input);
            require(sha(bytes).equals(name.substring(0, 64)), "Input hash differs");
            JsonObject capture;
            try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                capture = new JsonParser().parse(reader)
                    .getAsJsonObject();
            }
            require(
                "follow-core-reference-v1".equals(
                    capture.get("schema")
                        .getAsString()),
                "Unexpected reference schema");
            require(
                capture.get("complete")
                    .getAsBoolean(),
                "Incomplete original capture");
            output.addProperty("referenceSha256", sha(bytes));
            output.addProperty(
                "contractSha256",
                capture.get("contractSha256")
                    .getAsString());
            Set<String> identities = new HashSet<>();
            for (var element : capture.getAsJsonArray("cases")) {
                JsonObject row = element.getAsJsonObject();
                String id = row.get("id")
                    .getAsString();
                if (!id.startsWith("C04_native_")) continue;
                require(
                    id.equals("C04_native_safe") || id.equals("C04_native_blocked")
                        || id.equals("C04_native_head_blocked"),
                    "Unknown native scenario");
                int rebuild = row.get("rebuild")
                    .getAsInt();
                require(rebuild >= 0 && rebuild < 3 && identities.add(id + ":" + rebuild), "Duplicate native input");
                try (var fixture = new Fixture(world, row)) {
                    var random = new RecordedInts(row.getAsJsonArray("maidRandomCalls"));
                    var access = new Lease(fixture.maid);
                    var result = DryPlatformFollowTeleport.tryTeleport(fixture.maid, fixture.owner, random, access);
                    var actual = new JsonObject();
                    actual.addProperty("id", id);
                    actual.addProperty("rebuild", rebuild);
                    actual.add("result", new Gson().toJsonTree(result));
                    actual.addProperty("teleportReturned", result.status == DryPlatformFollowTeleport.Status.SUCCESS);
                    actual.addProperty("rngConsumed", random.index);
                    actual.addProperty("rngAvailable", random.samples.size());
                    actual.add("randomCalls", random.consumed);
                    actual.addProperty("navigationStops", access.stops);
                    actual.addProperty("memoryClearCallbacks", access.clears);
                    actual.add("finalPosition", fixture.relativePosition());
                    rows.add(actual);
                    if (id.equals("C04_native_safe") && rebuild == 0)
                        fixture.guards(checks, row.getAsJsonArray("maidRandomCalls"));
                }
            }
            require(identities.size() == 9, "Three original native scenarios with three rebuilds required");
        } catch (Exception | LinkageError problem) {
            failure = problem;
            output.addProperty(
                "errorType",
                problem.getClass()
                    .getSimpleName());
        }
        output.addProperty("executionComplete", failure == null);
        try {
            Path folder = checkedDirectory(Paths.get("tlmnh-dev", "follow-results"), true);
            String resultName = UUID.randomUUID() + ".json";
            byte[] bytes = output.toString()
                .getBytes(StandardCharsets.UTF_8);
            Files.write(folder.resolve(resultName), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var summary = new JsonObject();
            summary.addProperty("executionComplete", failure == null);
            summary.addProperty("equivalenceVerified", false);
            summary.addProperty("capture", "tlmnh-dev/follow-results/" + resultName);
            summary.addProperty("sha256", sha(bytes));
            summary.addProperty("bytes", bytes.length);
            summary.addProperty("rows", rows.size());
            TLMNotHard.LOG.info("TLMNH_FOLLOW_TELEPORT_NATIVE_CAPTURE {}", summary);
        } catch (Exception | LinkageError writing) {
            if (failure != null) writing.addSuppressed(failure);
            throw new IllegalStateException("Native follow capture could not be saved", writing);
        }
        if (failure != null)
            throw new IllegalStateException("Native follow capture incomplete; inspect retained artifact", failure);
    }

    private static Path checkedDirectory(Path relative, boolean create) throws Exception {
        Path root = Paths.get("")
            .toRealPath();
        Path result = root;
        for (Path part : relative) {
            result = result.resolve(part);
            if (create && !Files.exists(result, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(result);
            require(
                Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(result)
                    && result.toRealPath()
                        .equals(
                            result.toAbsolutePath()
                                .normalize()),
                "Redirected follow artifact directory");
        }
        return result;
    }

    private static JsonObject runtimeIdentity() throws Exception {
        return DevelopmentArtifactIdentity
            .capture(MaidFollowTeleportProbe.class, DryPlatformFollowTeleport.class, EntityMaid.class);
    }

    private static String sha(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class RecordedInts implements DryPlatformFollowTeleport.RandomInts {

        final JsonArray samples, consumed = new JsonArray();
        int index;

        RecordedInts(JsonArray samples) {
            this.samples = samples;
        }

        @Override
        public int nextInt(int bound) {
            require(index < samples.size(), "Original random stream exhausted");
            var sample = samples.get(index++)
                .getAsJsonObject();
            require(
                "nextInt".equals(
                    sample.get("method")
                        .getAsString())
                    && sample.get("bound")
                        .getAsInt() == bound,
                "Native random request differs from original stream");
            int value = sample.get("value")
                .getAsInt();
            require(
                sample.get("bound")
                    .getAsDouble() == bound
                    && sample.get("value")
                        .getAsDouble() == value
                    && value >= 0
                    && value < bound,
                "Original random value outside integer bound");
            consumed.add(new JsonParser().parse(sample.toString()));
            return value;
        }
    }

    private static final class Lease implements DryPlatformFollowTeleport.Access {

        final EntityMaid maid;
        final MaidPolicyRuntime runtime;
        final com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token token;
        boolean owned = true;
        boolean injectForeignPathOnClear;
        net.minecraft.pathfinding.PathEntity foreignPath;
        int stops, clears;

        Lease(EntityMaid maid) {
            this.maid = maid;
            runtime = maid.getBrainCoordinator()
                .getPolicyRuntime();
            token = runtime.getContext()
                .getToken();
        }

        @Override
        public boolean isCurrent() {
            return runtime.getContext()
                .isCurrent(token);
        }

        @Override
        public boolean canOwnNavigation() {
            return owned && maid.getNavigator()
                .getPath() == null;
        }

        @Override
        public void stopOwnedNavigation() {
            require(isCurrent() && canOwnNavigation(), "Lost component navigation lease");
            maid.getNavigator()
                .clearPathEntity();
            stops++;
        }

        @Override
        public void clearSuccessfulTeleportMemories() {
            clears++;
            if (injectForeignPathOnClear) {
                int x = net.minecraft.util.MathHelper.floor_double(maid.posX);
                int y = net.minecraft.util.MathHelper.floor_double(maid.posY);
                int z = net.minecraft.util.MathHelper.floor_double(maid.posZ);
                foreignPath = new net.minecraft.pathfinding.PathEntity(
                    new net.minecraft.pathfinding.PathPoint[] { new net.minecraft.pathfinding.PathPoint(x, y, z),
                        new net.minecraft.pathfinding.PathPoint(x + 1, y, z) });
                require(
                    maid.getNavigator()
                        .setPath(foreignPath, .5),
                    "Injected path was not installed");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {

        final WorldServer world;
        final EntityMaid maid;
        final EntityPlayerMP owner;
        final List<Placed> blocks = new ArrayList<>();
        final int baseX, baseZ, shiftY = -32;
        final int ownerX, ownerY, ownerZ;

        Fixture(WorldServer world, JsonObject row) {
            this.world = world;
            var spawn = world.getSpawnPoint();
            baseX = (spawn.posX >> 4 << 4) + 8;
            baseZ = (spawn.posZ >> 4 << 4) + 8;
            maid = new EntityMaid(world);
            maid.getBrainCoordinator()
                .setSuspended(true);
            owner = new EntityPlayerMP(
                MinecraftServer.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), "TLMNHFollowRef"),
                new ItemInWorldManager(world));
            var initial = row.getAsJsonObject("initialMaidPosition");
            var ownerAt = row.getAsJsonObject("ownerPosition");
            require(
                initial.get("x")
                    .getAsDouble() == .5
                    && initial.get("y")
                        .getAsDouble() == 192
                    && initial.get("z")
                        .getAsDouble() == .5
                    && ownerAt.get("x")
                        .getAsDouble() == 12.5
                    && ownerAt.get("y")
                        .getAsDouble() == 192
                    && ownerAt.get("z")
                        .getAsDouble() == .5,
                "Native scenario coordinates differ from admitted geometry");
            maid.setLocationAndAngles(baseX + .5, 160, baseZ + .5, 0, 0);
            owner.setLocationAndAngles(baseX + 12.5, 160, baseZ + .5, 0, 0);
            ownerX = baseX + 12;
            ownerY = 160;
            ownerZ = baseZ;
            try {
                var area = AxisAlignedBB.getBoundingBox(baseX - 1, 157, baseZ - 5, baseX + 18, 165, baseZ + 6);
                require(
                    world.getEntitiesWithinAABBExcludingEntity(null, area)
                        .isEmpty(),
                    "Native fixture contains other entities");
                String mode = row.getAsJsonObject("input")
                    .get("teleportMode")
                    .getAsString();
                for (int x = ownerX - 4; x <= ownerX + 4; x++) for (int z = ownerZ - 4; z <= ownerZ + 4; z++)
                    for (int y = ownerY - 2; y <= ownerY + 3; y++) preflight(x, y, z);
                preflight(baseX, 159, baseZ);
                for (int x = ownerX - 4; x <= ownerX + 4; x++) for (int z = ownerZ - 4; z <= ownerZ + 4; z++) {
                    place(x, ownerY - 1, z, Blocks.stone);
                    if (mode.equals("native_blocked"))
                        for (int y = ownerY; y <= ownerY + 2; y++) place(x, y, z, Blocks.stone);
                    else if (mode.equals("native_head_blocked"))
                        for (int y = ownerY + 1; y <= ownerY + 2; y++) place(x, y, z, Blocks.stone);
                    else require(mode.equals("native_safe"), "Unknown native geometry");
                }
                place(baseX, 159, baseZ, Blocks.stone);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        void preflight(int x, int y, int z) {
            require(
                world.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4),
                "Native fixture chunk unloaded");
            require(
                world.getBlock(x, y, z) == Blocks.air && world.getBlockMetadata(x, y, z) == 0
                    && world.getTileEntity(x, y, z) == null,
                "Native fixture requires untouched air");
        }

        void place(int x, int y, int z, net.minecraft.block.Block block) {
            require(
                world.getBlock(x, y, z) == Blocks.air && world.getBlockMetadata(x, y, z) == 0
                    && world.getTileEntity(x, y, z) == null,
                "Placement source changed");
            var placed = new Placed(x, y, z, block);
            blocks.add(placed);
            require(world.setBlock(x, y, z, block, 0, 2), "Native fixture placement rejected");
        }

        JsonObject relativePosition() {
            var position = new JsonObject();
            position.addProperty("x", maid.posX - baseX);
            position.addProperty("y", maid.posY - shiftY);
            position.addProperty("z", maid.posZ - baseZ);
            return position;
        }

        void guards(JsonArray output, JsonArray originalRandom) {
            maid.setLocationAndAngles(baseX + .5, 160, baseZ + .5, 0, 0);
            var injected = new Lease(maid);
            injected.injectForeignPathOnClear = true;
            var afterMove = DryPlatformFollowTeleport
                .tryTeleport(maid, owner, new RecordedInts(originalRandom), injected);
            require(
                afterMove.status == DryPlatformFollowTeleport.Status.OWNERSHIP_LOST && afterMove.moved
                    && maid.getNavigator()
                        .getPath() == injected.foreignPath,
                "Ownership loss after a real move was reported as success or erased the replacement path");
            output.add(new Gson().toJsonTree(afterMove));
            maid.getNavigator()
                .clearPathEntity();
            maid.setLocationAndAngles(baseX + .5, 160, baseZ + .5, 0, 0);
            var access = new Lease(maid);
            var noRandom = new RecordedInts(new JsonArray());
            place(ownerX + 4, ownerY + 3, ownerZ + 4, Blocks.dirt);
            var before = relativePosition();
            var pathBefore = maid.getNavigator()
                .getPath();
            var outside = DryPlatformFollowTeleport.tryTeleport(maid, owner, noRandom, access);
            require(
                outside.status == DryPlatformFollowTeleport.Status.OUT_OF_SCOPE && outside.rngCalls == 0
                    && !outside.moved,
                "Unknown block became a scored failed candidate");
            output.add(rejectedGuard(outside, access, before, pathBefore));
            var stale = new Lease(maid);
            stale.runtime.invalidate(MaidPolicyRuntime.InvalidationReason.RESET);
            var rejected = DryPlatformFollowTeleport.tryTeleport(maid, owner, noRandom, stale);
            require(
                rejected.status == DryPlatformFollowTeleport.Status.STALE && rejected.rngCalls == 0 && !rejected.moved,
                "Stale token executed teleport");
            output.add(rejectedGuard(rejected, stale, before, pathBefore));
            var unowned = new Lease(maid);
            unowned.owned = false;
            var lost = DryPlatformFollowTeleport.tryTeleport(maid, owner, noRandom, unowned);
            require(
                lost.status == DryPlatformFollowTeleport.Status.OWNERSHIP_LOST && lost.rngCalls == 0 && !lost.moved,
                "Unowned navigation executed teleport");
            output.add(rejectedGuard(lost, unowned, before, pathBefore));
        }

        JsonObject rejectedGuard(DryPlatformFollowTeleport.Result result, Lease lease, JsonObject before,
            net.minecraft.pathfinding.PathEntity pathBefore) {
            var after = relativePosition();
            boolean pathUnchanged = maid.getNavigator()
                .getPath() == pathBefore;
            require(
                before.equals(after) && pathUnchanged && lease.stops == 0 && lease.clears == 0,
                "Rejected guard changed actual position, navigation or memory callbacks");
            var actual = new Gson().toJsonTree(result)
                .getAsJsonObject();
            actual.add("positionBefore", before);
            actual.add("positionAfter", after);
            actual.addProperty("pathIdentityUnchanged", pathUnchanged);
            actual.addProperty("navigationStops", lease.stops);
            actual.addProperty("memoryClearCallbacks", lease.clears);
            return actual;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                var at = blocks.get(i);
                try {
                    require(
                        world.getBlock(at.x, at.y, at.z) == at.block && world.getBlockMetadata(at.x, at.y, at.z) == 0,
                        "Fixture changed externally; refusing overwrite");
                    require(world.setBlockToAir(at.x, at.y, at.z), "Native fixture cleanup failed");
                } catch (RuntimeException problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                }
            }
            blocks.clear();
            maid.setDead();
            owner.setDead();
            if (failure != null) throw failure;
        }
    }

    private static final class Placed {

        final int x, y, z;
        final net.minecraft.block.Block block;

        Placed(int x, int y, int z, net.minecraft.block.Block block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }
}
