package com.tianluoqaq.tlmnh.reference;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidPathNavigation;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 实际原版类和 Behavior 方法，流体/骑乘/可见输入为显式受控依赖，不自然 tick Brain */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class SwimReferenceProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static volatile boolean pending;
    private SwimReferenceProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.swimProbe") || event.phase != TickEvent.Phase.END || pending) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-swim-reference");
                for (String name : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(name));
                TouhouLittleMaid.LOGGER.info("TLMNH_SWIM_REFERENCE_READY");
            }
            try (var stream = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var files = new ArrayList<Path>(); stream.forEach(files::add); files.sort(Path::compareTo);
                if (!files.isEmpty()) handle(files.get(0));
            }
        } catch (Exception | LinkageError failure) { TouhouLittleMaid.LOGGER.error("TLMNH_SWIM_REFERENCE_IO_FAIL", failure); }
    }

    private static void handle(Path file) throws Exception {
        String name = file.getFileName().toString();
        var response = new JsonObject();
        try {
            require(name.matches("[A-Za-z0-9_-]{1,100}\\.json") && Files.size(file) <= 16384, "Bounded request required");
            var request = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var mc = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> { response.addProperty("worldLoaded", mc.getSingleplayerServer() != null); response.addProperty("pending", pending); }
                case "lab_create" -> response = ReferenceScene.createLab();
                case "lab_open" -> response = ReferenceScene.openLab();
                case "shutdown" -> mc.stop();
                case "capture_swim" -> {
                    var server = mc.getSingleplayerServer();
                    require(server != null && mc.player != null && mc.player.isSpectator()
                        && mc.player.getAbilities().invulnerable && server.getWorldData().getLevelName().equals("TLMNH-Reference-Lab"),
                        "Protected dedicated reference lab required");
                    String fault = request.has("fault") ? request.get("fault").getAsString() : "none";
                    require(Set.of("none", "pose-ground-ignored", "jump-underwater-allowed").contains(fault), "Unknown fault");
                    require(request.get("repetitions").getAsInt() == 3, "Three independent rebuilds required");
                    pending = true; Files.delete(file); mc.setScreen(null);
                    server.execute(() -> capture(server, name, fault)); return;
                }
                default -> throw new IllegalArgumentException("Unknown swim request");
            }
            response.addProperty("status", "complete");
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive"); response.addProperty("errorType", failure.getClass().getSimpleName());
            TouhouLittleMaid.LOGGER.error("TLMNH_SWIM_REFERENCE_FAIL", failure);
        }
        response.addProperty("request", name); write(root.resolve("responses").resolve(name), response); Files.deleteIfExists(file);
    }

    private static void capture(MinecraftServer server, String name, String fault) {
        var response = new JsonObject();
        try {
            require(server.isSameThread(), "Server thread required");
            var level = server.overworld();
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.getChunk(cx, cz);
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 3; z++) for (int y = 190; y <= 196; y++)
                require(level.isEmptyBlock(new BlockPos(x, y, z)), "Pure air component region required");
            var report = new JsonObject();
            report.addProperty("schema", "swim-reference-capture-v1"); report.addProperty("complete", true);
            report.addProperty("naturalBrainVerified", false); report.addProperty("fault", fault);
            report.add("identity", SwimReferenceIdentity.capture());
            var rows = new JsonArray(); report.add("cases", rows);
            for (int rebuild = 0; rebuild < 3; rebuild++) {
                for (int bits = 0; bits < 32; bits++) rows.add(pose(level, bits, rebuild, "air", fault));
                for (String geometry : List.of("floor", "wedged")) rows.add(pose(level, 0, rebuild, geometry, fault));
                for (String scenario : List.of("surface", "underwater", "navigation-busy", "threshold-equal", "lava", "changes"))
                    rows.add(jump(level, scenario, rebuild, fault));
            }
            Path output = root.resolve("captures").resolve(name); write(output, report);
            response.addProperty("status", "complete"); response.addProperty("capture", "captures/" + name);
            response.addProperty("captureSha256", SwimReferenceIdentity.sha(Files.readAllBytes(output)));
        } catch (Exception | LinkageError failure) {
            response.addProperty("status", "inconclusive"); response.addProperty("errorType", failure.getClass().getSimpleName());
            TouhouLittleMaid.LOGGER.error("TLMNH_SWIM_CAPTURE_FAIL", failure);
        } finally {
            response.addProperty("request", name);
            try { write(root.resolve("responses").resolve(name), response); } catch (Exception failure) { TouhouLittleMaid.LOGGER.error("Swim response failed", failure); }
            pending = false;
        }
    }

    private static JsonObject pose(ServerLevel level, int bits, int rebuild, String geometry, String fault) throws Exception {
        var maid = new ProbeMaid(level);
        var row = new JsonObject(); row.addProperty("type", "pose"); row.addProperty("id", bits + "/" + geometry); row.addProperty("rebuild", rebuild);
        boolean want = (bits & 1) != 0, ground = (bits & 2) != 0;
        maid.sitting = (bits & 4) != 0; maid.passenger = (bits & 8) != 0; maid.sleep = (bits & 16) != 0;
        boolean geometryCase = !geometry.equals("air");
        Pose initial = geometryCase ? Pose.SWIMMING : maid.sleep ? Pose.SLEEPING : Pose.STANDING;
        maid.setPose(initial); maid.setPos(.5, 192, .5); maid.readyForResize();
        maid.setOnGround(ground && !fault.equals("pose-ground-ignored"));
        var input = new JsonObject(); input.addProperty("want", want); input.addProperty("ground", ground);
        input.addProperty("sitting", maid.sitting); input.addProperty("passenger", maid.passenger); input.addProperty("sleep", maid.sleep);
        input.addProperty("initialPose", initial.name()); input.addProperty("geometry", geometry); row.add("input", input);
        var states = new JsonArray(); row.add("states", states);
        try {
            if (geometryCase) level.setBlock(new BlockPos(0, 191, 0), Blocks.STONE.defaultBlockState(), 2);
            if (geometry.equals("wedged")) level.setBlock(new BlockPos(0, 193, 0), Blocks.STONE.defaultBlockState(), 2);
            maid.getSwimManager().setWantToSwim(want); maid.getSwimManager().updateSwimming(); states.add(poseState(maid));
            maid.getSwimManager().setWantToSwim(false); maid.getSwimManager().updateSwimming(); states.add(poseState(maid));
        } finally {
            if (geometryCase) level.setBlock(new BlockPos(0, 191, 0), Blocks.AIR.defaultBlockState(), 2);
            if (geometry.equals("wedged")) level.setBlock(new BlockPos(0, 193, 0), Blocks.AIR.defaultBlockState(), 2);
            maid.discard();
        }
        return row;
    }

    private static JsonObject poseState(ProbeMaid maid) {
        var state = new JsonObject(); var dimensions = maid.getDimensions(maid.getPose());
        state.addProperty("swimming", maid.isSwimming()); state.addProperty("pose", maid.getPose().name());
        state.addProperty("width", dimensions.width); state.addProperty("height", dimensions.height); state.addProperty("eye", maid.getEyeHeight());
        state.addProperty("x", maid.getX()); state.addProperty("y", maid.getY()); state.addProperty("z", maid.getZ()); return state;
    }

    private static JsonObject jump(ServerLevel level, String scenario, int rebuild, String fault) throws Exception {
        var maid = new ProbeMaid(level); maid.setNavigation(new ControlledNavigation(maid, level));
        var worldRandom = Level.class.getDeclaredField("random"); worldRandom.setAccessible(true);
        var original = (RandomSource) worldRandom.get(level);
        var world = new Feed(original, false); var entity = new Feed(maid.getRandom(), true); maid.feed = entity;
        var task = new MaidSwimJumpTask(.8F); var states = new JsonArray();
        var row = new JsonObject(); row.addProperty("type", "jump"); row.addProperty("id", scenario); row.addProperty("rebuild", rebuild); row.add("states", states);
        try {
            worldRandom.set(level, world);
            long[] times = {0, 1, 60, 61, 62, 63};
            for (int index = 0; index < times.length; index++) {
                maid.wet = !scenario.equals("lava"); maid.lava = scenario.equals("lava");
                maid.fluidHeight = scenario.equals("threshold-equal") ? .4 : .8;
                maid.submerged = scenario.equals("underwater") || scenario.equals("changes") && index == 2;
                maid.done = !scenario.equals("navigation-busy") && !(scenario.equals("changes") && index == 4);
                var state = new JsonObject(); state.addProperty("time", times[index]); state.addProperty("water", maid.wet);
                state.addProperty("height", maid.fluidHeight); state.addProperty("lava", maid.lava); state.addProperty("underwater", maid.submerged); state.addProperty("done", maid.done);
                if (fault.equals("jump-underwater-allowed")) maid.submerged = false;
                if (task.getStatus() == Behavior.Status.STOPPED) task.tryStart(level, maid, times[index]);
                if (task.getStatus() == Behavior.Status.RUNNING) task.tickOrStop(level, maid, times[index]);
                state.addProperty("status", task.getStatus().name()); state.addProperty("end", read(Behavior.class, "endTimestamp", task).toString());
                state.addProperty("jump", (Boolean) read(JumpControl.class, "jump", maid.getJumpControl()));
                state.addProperty("worldCalls", world.ints); state.addProperty("entityCalls", entity.floats); states.add(state);
                maid.getJumpControl().tick();
            }
        } finally { worldRandom.set(level, original); maid.discard(); }
        return row;
    }

    private static final class ProbeMaid extends EntityMaid {
        boolean sitting, passenger, sleep, wet, lava, submerged, done = true;
        double fluidHeight;
        Feed feed;
        ProbeMaid(ServerLevel level) { super(level); }
        void readyForResize() { this.firstTick = false; }
        @Override public boolean isMaidInSittingPose() { return sitting; }
        @Override public boolean isPassenger() { return passenger; }
        @Override public boolean isSleeping() { return sleep; }
        @Override public boolean isInWater() { return wet; }
        @Override public boolean isInLava() { return lava; }
        @Override public boolean isUnderWater() { return submerged; }
        @Override public double getFluidHeight(TagKey<Fluid> tag) { return fluidHeight; }
        @Override public RandomSource getRandom() { return feed == null ? super.getRandom() : feed; }
    }
    private static final class ControlledNavigation extends MaidPathNavigation {
        private final ProbeMaid maid;
        ControlledNavigation(ProbeMaid maid, Level level) { super(maid, level); this.maid = maid; }
        @Override public boolean isDone() { return maid.done; }
    }
    private static final class Feed implements RandomSource {
        final RandomSource delegate; final boolean entity; int ints, floats;
        Feed(RandomSource delegate, boolean entity) { this.delegate = delegate; this.entity = entity; }
        @Override public int nextInt(int bound) { require(!entity && bound == 1, "Unexpected integer request"); ints++; return 0; }
        @Override public float nextFloat() { require(entity, "Unexpected float request"); return new float[]{0, .8F, .7999F, .99F}[floats++ % 4]; }
        @Override public RandomSource fork() { return delegate.fork(); }
        @Override public PositionalRandomFactory forkPositional() { return delegate.forkPositional(); }
        @Override public void setSeed(long seed) { delegate.setSeed(seed); }
        @Override public int nextInt() { throw new IllegalStateException("Unexpected RNG"); }
        @Override public long nextLong() { throw new IllegalStateException("Unexpected RNG"); }
        @Override public boolean nextBoolean() { throw new IllegalStateException("Unexpected RNG"); }
        @Override public double nextDouble() { throw new IllegalStateException("Unexpected RNG"); }
        @Override public double nextGaussian() { throw new IllegalStateException("Unexpected RNG"); }
    }
    private static Object read(Class<?> owner, String name, Object value) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void write(Path file, JsonObject value) throws Exception {
        require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS), "Refusing evidence overwrite");
        Files.writeString(file, GSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
