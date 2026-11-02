package com.tianluoqaq.tlmnh.reference.sleep;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.block.BlockMaidBed;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.init.InitPoi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 原版自然睡眠可行性采集，不手动调用Brain、设置睡姿或把轨迹完成当测评通过 */
@GameTestHolder("tlmnh_sleep_natural")
@PrefixGameTestTemplate(false)
@Mod.EventBusSubscriber(modid = "touhou_little_maid")
public final class SleepNaturalGameTests {

    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();
    private static Session active;

    private SleepNaturalGameTests() {}

    @GameTest(template = "cell", timeoutTicks = 620, batch = "sleep_natural")
    public static void collectThreeFreshHomeActors(GameTestHelper helper) {
        collect(helper, new Session(helper));
    }

    static void collectMechanism(GameTestHelper helper, String mechanism) {
        collect(helper, new SleepMechanismGameTests.Session(helper, mechanism));
    }

    private static void collect(GameTestHelper helper, Session session) {
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.sleepNaturalProbe") && active == null,
            "Dedicated sleep reference configuration required");
        active = session;
        helper.onEachTick(() -> {
            if (session.failure != null) helper.fail(session.failure);
            if (session.complete) helper.succeed();
        });
        try {
            session.setup();
            helper.runAfterDelay(
                session.contract.get("warmupTicks")
                    .getAsLong(),
                () -> {
                    try { session.spawn(); }
                    catch (Exception | LinkageError | AssertionError failure) { session.abort(failure); }
                });
        } catch (Exception failure) {
            session.abort(failure);
        }
    }

    @SubscribeEvent
    public static void living(LivingEvent.LivingTickEvent event) {
        if (active != null && event.getEntity() == active.maid) active.livingTicks++;
    }

    @SubscribeEvent
    public static void level(TickEvent.LevelTickEvent event) {
        var session = active;
        if (session == null || event.level != session.level) return;
        try {
            if (event.phase == TickEvent.Phase.START) session.beforeWorld();
            else session.observe();
        } catch (Exception | LinkageError | AssertionError failure) {
            session.abort(failure);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null) active.abort(new IllegalStateException("Server stopped before observation completion"));
    }

    static class Session {

        final GameTestHelper helper;
        final ServerLevel level;
        final JsonObject contract;
        final JsonObject output = new JsonObject();
        final JsonArray runs = new JsonArray();
        final UUID offlineOwner = UUID.randomUUID();
        final long oldTime;
        final boolean oldDaylight, oldSpawning;
        final Path capture;
        EntityMaid maid;
        BlockPos head, foot, home, origin;
        JsonObject row;
        JsonArray frames;
        int livingTicks, previousAge, repetition;
        boolean complete;
        String failure;

        Session(GameTestHelper helper) {
            this(helper, "/tlmnh-sleep/contract.json", "sleep-natural-reference-feasibility-v1", "sleep-natural-");
        }

        Session(GameTestHelper helper, String resource, String schema, String prefix) {
            this.helper = helper;
            level = helper.getLevel();
            try (var stream = SleepNaturalGameTests.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing sleep contract");
                byte[] bytes = stream.readAllBytes();
                contract = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
                output.addProperty("contractSha256", sha(bytes));
                output.add("contract", contract.deepCopy());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            oldTime = level.getDayTime();
            oldDaylight = level.getGameRules()
                .getBoolean(GameRules.RULE_DAYLIGHT);
            oldSpawning = level.getGameRules()
                .getBoolean(GameRules.RULE_DOMOBSPAWNING);
            capture = Path.of("captures", prefix + System.nanoTime() + ".json");
            output.addProperty("schema", schema);
            output.addProperty("benchmarkVerdict", "NOT_EVALUATED");
            output.addProperty("completeB10Accepted", false);
            output.addProperty("randomSourcesReset", false);
            output.add("runs", runs);
        }

        void setup() {
            origin = helper.absolutePos(BlockPos.ZERO);
            head = absolute("bedHead");
            foot = absolute("bedFoot");
            home = absolute("homeCenter");
            level.getGameRules()
                .getRule(GameRules.RULE_DAYLIGHT)
                .set(false, level.getServer());
            level.getGameRules()
                .getRule(GameRules.RULE_DOMOBSPAWNING)
                .set(false, level.getServer());
            level.setDayTime(
                contract.get("dayTime")
                    .getAsLong());
            for (int x = 0; x < 17; x++) for (int z = 0; z < 9; z++) helper.setBlock(x, 0, z, Blocks.STONE);
            var bed = InitBlocks.MAID_BED.get()
                .defaultBlockState()
                .setValue(BlockMaidBed.FACING, Direction.NORTH)
                .setValue(BlockMaidBed.OCCUPIED, false);
            level.setBlock(foot, bed.setValue(BlockMaidBed.PART, BedPart.FOOT), 3);
            level.setBlock(head, bed.setValue(BlockMaidBed.PART, BedPart.HEAD), 3);
        }

        BlockPos absolute(String key) {
            var a = contract.getAsJsonArray(key);
            return helper.absolutePos(
                new BlockPos(
                    a.get(0)
                        .getAsInt(),
                    a.get(1)
                        .getAsInt(),
                    a.get(2)
                        .getAsInt()));
        }

        void spawn() {
            helper.assertTrue(
                maid == null && level.getServer()
                    .getPlayerList()
                    .getPlayer(offlineOwner) == null,
                "Previous actor or unexpected connected owner");
            helper.assertTrue(
                level.getBlockState(head)
                    .is(InitBlocks.MAID_BED.get()),
                "Bed head missing");
            long poi = level.getPoiManager()
                .getInRange(type -> type.get() == InitPoi.MAID_BED.get(), head, 1, PoiManager.Occupancy.ANY)
                .filter(
                    p -> p.getPos()
                        .equals(head))
                .count();
            helper.assertTrue(poi == expectedPoiCount(), "Actual native bed POI count differs from the scene input");
            maid = new EntityMaid(level);
            maid.setOwnerUUID(offlineOwner);
            maid.setTame(true);
            maid.setInSittingPose(false);
            maid.setTask(TaskManager.getIdleTask());
            maid.setSchedule(MaidSchedule.NIGHT);
            maid.setPickup(false);
            var pos = absolute("maidSpawnBlock");
            maid.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            maid.setHomeModeEnable(true);
            var schedule = maid.getSchedulePos();
            schedule.setWorkPos(home);
            schedule.setIdlePos(home);
            schedule.setSleepPos(home);
            schedule.setDimension(
                level.dimension()
                    .location());
            schedule.setConfigured(true);
            schedule.restrictTo(maid);
            beforeSpawn();
            helper.assertTrue(level.addFreshEntity(maid), "Native maid registration failed");
            helper.assertTrue(
                maid.getClass() == EntityMaid.class && !maid.isNoAi(),
                "Exact original natural maid required");
            livingTicks = previousAge = 0;
            row = new JsonObject();
            frames = new JsonArray();
            row.addProperty("repetition", repetition);
            row.addProperty(
                "uuid",
                maid.getUUID()
                    .toString());
            row.addProperty(
                "entityClass",
                maid.getClass()
                    .getName());
            row.addProperty(
                "brainClass",
                maid.getBrain()
                    .getClass()
                    .getName());
            row.addProperty("actualRestrictRadius", maid.getRestrictRadius());
            row.addProperty("nativeBedPoiCount", poi);
            row.add("frames", frames);
            row.add("attributeInputs", SleepAttributeSnapshot.capture(maid));
            runs.add(row);
            if (repetition == 0) output.add("identity", identity());
            afterSpawn();
        }

        int expectedPoiCount() { return 1; }

        protected void beforeSpawn() {}

        protected void afterSpawn() {}

        protected void afterFrame(JsonObject frame) {}

        void beforeWorld() {}

        void observe() throws Exception {
            if (maid == null || maid.tickCount == 0) return;
            helper.assertTrue(
                level.getEntity(maid.getUUID()) == maid && maid.isAlive() && !maid.isNoAi(),
                "Actor lost natural registration");
            helper.assertTrue(
                maid.tickCount == previousAge + 1 && livingTicks == 1,
                "Expected one real entity/Living tick per frame");
            helper.assertTrue(
                maid.getOwner() == null && maid.isHomeModeEnable() && maid.getSchedule() == MaidSchedule.NIGHT,
                "Offline home input changed");
            helper.assertTrue(
                level.getDayTime() == contract.get("dayTime")
                    .getAsLong(),
                "Frozen day-time input changed");
            var frame = new JsonObject();
            frame.addProperty("frame", frames.size());
            frame.addProperty("age", maid.tickCount);
            frame.addProperty("gameTime", level.getGameTime());
            frame.addProperty("dayTime", level.getDayTime());
            frame.add(
                "position",
                vector(
                    maid.position()
                        .subtract(origin.getX(), origin.getY(), origin.getZ())));
            frame.add("motion", vector(maid.getDeltaMovement()));
            frame.addProperty("sleeping", maid.isSleeping());
            frame.addProperty("health", maid.getHealth());
            frame.addProperty("livingTicks", livingTicks);
            frame.addProperty(
                "activity",
                maid.getScheduleDetail()
                    .toString());
            frame.addProperty(
                "activeBrainActivities",
                maid.getBrain()
                    .getActiveActivities()
                    .toString());
            frame.addProperty(
                "bedOccupied",
                level.getBlockState(head).is(InitBlocks.MAID_BED.get())
                    && level.getBlockState(head).getValue(BlockMaidBed.OCCUPIED));
            frame.addProperty(
                "walkTarget",
                maid.getBrain()
                    .hasMemoryValue(MemoryModuleType.WALK_TARGET));
            frame.addProperty(
                "navigationDone",
                maid.getNavigation()
                    .isDone());
            frames.add(frame);
            afterFrame(frame);
            previousAge = maid.tickCount;
            livingTicks = 0;
            if (frames.size() != contract.get("framesPerActor")
                .getAsInt()) return;
            cleanupActor();
            repetition++;
            if (repetition < contract.get("repetitions")
                .getAsInt()) {
                spawn();
                return;
            }
            restoreWorld();
            output.addProperty("observationComplete", true);
            output.addProperty("cleanupComplete", true);
            write();
            complete = true;
            active = null;
            System.out.println("TLMNH_SLEEP_NATURAL_CAPTURE complete=true actors=" + repetition + " file=" + capture);
        }

        void cleanupActor() {
            if (maid == null) return;
            if (maid.isSleeping()) maid.stopSleeping();
            maid.discard();
            helper.assertTrue(
                level.getEntity(maid.getUUID()) == null || maid.isRemoved(),
                "Original actor cleanup failed");
            maid = null;
        }

        void restoreWorld() {
            level.getGameRules()
                .getRule(GameRules.RULE_DAYLIGHT)
                .set(oldDaylight, level.getServer());
            level.getGameRules()
                .getRule(GameRules.RULE_DOMOBSPAWNING)
                .set(oldSpawning, level.getServer());
            level.setDayTime(oldTime);
        }

        void abort(Throwable error) {
            failure = error.toString();
            output.addProperty("observationComplete", false);
            output.addProperty("error", failure);
            try {
                cleanupActor();
                restoreWorld();
                output.addProperty("cleanupComplete", true);
            } catch (Throwable cleanup) {
                output.addProperty("cleanupError", cleanup.toString());
            }
            try {
                write();
            } catch (Exception io) {
                System.err.println("TLMNH_SLEEP_NATURAL_IO_FAIL " + io);
            }
            active = null;
            error.printStackTrace();
        }

        void write() throws Exception {
            Files.createDirectories(capture.getParent());
            Files.writeString(capture, GSON.toJson(output) + "\n", StandardCharsets.UTF_8);
        }
    }

    private static JsonObject identity() {
        return identity("tlmnh.sleep.buildIdentity");
    }

    static JsonObject identity(String property) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(System.getProperty(property)));
            var manifest = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            var agent = Class.forName(
                "com.tianluoqaq.tlmnh.geometry.identity.GeometryDefinitionAgent",
                true,
                ClassLoader.getSystemClassLoader());
            var startup = (Map<?, ?>) agent.getMethod("startup")
                .invoke(null);
            if (!sha(bytes).equals(startup.get("manifestSha256")))
                throw new IllegalStateException("Premain manifest mismatch");
            var classes = new LinkedHashMap<String, Object>();
            for (var entry : manifest.getAsJsonObject("compiledClasses")
                .entrySet()) {
                var type = Class.forName(entry.getKey(), false, SleepNaturalGameTests.class.getClassLoader());
                try (var stream = type.getResourceAsStream(
                    "/" + entry.getKey()
                        .replace('.', '/') + ".class")) {
                    String resource = sha(stream.readAllBytes());
                    if (!resource.equals(
                        entry.getValue()
                            .getAsString()))
                        throw new IllegalStateException("Compiled resource mismatch " + entry.getKey());
                    classes.put(
                        entry.getKey(),
                        Map.of(
                            "resourceSha256",
                            resource,
                            "definition",
                            agent.getMethod("identity", Class.class)
                                .invoke(null, type)));
                }
            }
            var result = new JsonObject();
            result.addProperty("buildIdentitySha256", sha(bytes));
            result.add("agent", GSON.toJsonTree(startup));
            result.add("classes", GSON.toJsonTree(classes));
            result.addProperty("wholeClasspathOrPostDefinitionBytesVerified", false);
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("Original sleep identity rejected", failure);
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
    }

    private static JsonArray vector(Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
            throw new IllegalStateException("Nonfinite original sleep observation");
        var result = new JsonArray();
        result.add(value.x);
        result.add(value.y);
        result.add(value.z);
        return result;
    }
}
