package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidSwimJumpTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidSwimManager;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.entity.MaidEntityPose;
import com.tianluoqaq.tlmnh.common.entity.MaidPoseDimensions;

/** 执行实际移植类，仅消费原版采集的条件输入，不读取原版策略结果 */
public final class SwimCoreReplayMain {

    private SwimCoreReplayMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Reference and fresh output required");
        var input = Paths.get(args[0])
            .toAbsolutePath()
            .normalize();
        var output = Paths.get(args[1])
            .toAbsolutePath()
            .normalize();
        if (input.equals(output) || Files.exists(output)) throw new IllegalArgumentException("Fresh output required");
        JsonObject reference;
        try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            reference = LookCoreReplayMain.parseStrict(reader);
        }
        if (!reference.get("schema")
            .getAsString()
            .equals("swim-reference-capture-v1")) throw new IllegalArgumentException("Unsupported input schema");
        var result = new JsonObject();
        result.addProperty("schema", "swim-port-replay-v1");
        result.addProperty("naturalBrainVerified", false);
        var rows = new JsonArray();
        result.add("cases", rows);
        for (var value : reference.getAsJsonArray("cases")) {
            var source = value.getAsJsonObject();
            var row = new JsonObject();
            for (String key : new String[] { "type", "id", "rebuild" }) row.add(key, source.get(key));
            var states = new JsonArray();
            row.add("states", states);
            var actor = new Actor();
            if (source.get("type")
                .getAsString()
                .equals("pose")) {
                var fields = source.getAsJsonObject("input");
                actor.ground = fields.get("ground")
                    .getAsBoolean();
                actor.sitting = fields.get("sitting")
                    .getAsBoolean();
                actor.passenger = fields.get("passenger")
                    .getAsBoolean();
                actor.sleep = fields.get("sleep")
                    .getAsBoolean();
                actor.pose = MaidEntityPose.valueOf(
                    fields.get("initialPose")
                        .getAsString());
                if (actor.pose != MaidEntityPose.STANDING) actor.refreshEye();
                actor.geometry = fields.get("geometry")
                    .getAsString();
                var manager = new MaidSwimManager(actor);
                manager.setWantToSwim(
                    fields.get("want")
                        .getAsBoolean());
                manager.updateSwimming();
                states.add(actor.poseState());
                manager.setWantToSwim(false);
                manager.updateSwimming();
                states.add(actor.poseState());
            } else if (source.get("type")
                .getAsString()
                .equals("jump")) {
                    var task = new MaidSwimJumpTask(.8F);
                    for (var frame : source.getAsJsonArray("states")) {
                        var fields = frame.getAsJsonObject();
                        long time = fields.get("time")
                            .getAsLong();
                        actor.wet = fields.get("water")
                            .getAsBoolean();
                        actor.fluidHeight = fields.get("height")
                            .getAsDouble();
                        actor.lava = fields.get("lava")
                            .getAsBoolean();
                        actor.submerged = fields.get("underwater")
                            .getAsBoolean();
                        actor.done = fields.get("done")
                            .getAsBoolean();
                        actor.jump = false;
                        task.tick(actor, time);
                        var state = new JsonObject();
                        for (String key : new String[] { "time", "water", "height", "lava", "underwater", "done" })
                            state.add(key, fields.get(key));
                        state.addProperty("status", task.isRunning() ? "RUNNING" : "STOPPED");
                        state.addProperty("end", Long.toString(task.getEndTimestamp()));
                        state.addProperty("jump", actor.jump);
                        state.addProperty("worldCalls", actor.ints);
                        state.addProperty("entityCalls", actor.floats);
                        states.add(state);
                    }
                } else throw new IllegalArgumentException("Unknown case");
            rows.add(row);
        }
        Files.createDirectories(output.getParent());
        Files.write(
            output,
            (new GsonBuilder().serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(result) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class Actor implements MaidSwimmingActor, MaidSwimJumpActor {

        boolean ground, sitting, passenger, sleep, swimming, wet, lava, submerged, done, jump;
        double fluidHeight, y = 192;
        int ints, floats;
        float eye = 1.5F * .85F;
        MaidEntityPose pose = MaidEntityPose.STANDING;
        String geometry;

        private void pose(MaidEntityPose next) {
            if (pose == next) return;
            var old = pose;
            pose = next;
            refreshEye();
            if (next.width <= old.width && next.height <= old.height) return;
            double dx = Math.max(0F, next.width - old.width) + 1e-6;
            double dy = Math.max(0F, next.height - old.height) + 1e-6;
            double cy = y + old.height / 2.0;
            var region = AxisAlignedBB
                .getBoundingBox(.5 - dx / 2, cy - dy / 2, .5 - dx / 2, .5 + dx / 2, cy + dy / 2, .5 + dx / 2);
            var blocks = new ArrayList<AxisAlignedBB>();
            if (!geometry.equals("air")) blocks.add(AxisAlignedBB.getBoundingBox(0, 191, 0, 1, 192, 1));
            if (geometry.equals("wedged")) blocks.add(AxisAlignedBB.getBoundingBox(0, 193, 0, 1, 194, 1));
            var center = MaidPoseDimensions
                .closestCenter(region, Vec3.createVectorHelper(.5, cy, .5), blocks, next.width, next.height);
            if (center != null) y = center.yCoord - next.height / 2.0;
        }

        JsonObject poseState() {
            var value = new JsonObject();
            value.addProperty("swimming", swimming);
            value.addProperty("pose", pose.name());
            value.addProperty("width", pose.width);
            value.addProperty("height", pose.height);
            value.addProperty("eye", eye);
            value.addProperty("x", .5);
            value.addProperty("y", y);
            value.addProperty("z", .5);
            return value;
        }

        private void refreshEye() {
            eye = pose == MaidEntityPose.SLEEPING ? .2F : pose.height * (sitting ? .65F : .85F);
        }

        @Override
        public void configureWaterPath() {}

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public boolean onGround() {
            return ground;
        }

        @Override
        public boolean isSitting() {
            return sitting;
        }

        @Override
        public boolean isPassenger() {
            return passenger;
        }

        @Override
        public boolean isSleeping() {
            return sleep;
        }

        @Override
        public void setSwimming(boolean value) {
            swimming = value;
        }

        @Override
        public void setSwimmingPose() {
            pose(MaidEntityPose.SWIMMING);
        }

        @Override
        public void setStandingPose() {
            pose(MaidEntityPose.STANDING);
        }

        @Override
        public boolean inWater() {
            return wet;
        }

        @Override
        public double waterHeight() {
            return fluidHeight;
        }

        @Override
        public double fluidJumpThreshold() {
            return .4;
        }

        @Override
        public boolean inLava() {
            return lava;
        }

        @Override
        public boolean underWater() {
            return submerged;
        }

        @Override
        public boolean navigationDone() {
            return done;
        }

        @Override
        public int nextWorldInt(int bound) {
            if (bound != 1) throw new IllegalArgumentException("Unexpected world random bound");
            ints++;
            return 0;
        }

        @Override
        public float nextEntityFloat() {
            return new float[] { 0, .8F, .7999F, .99F }[floats++ % 4];
        }

        @Override
        public void requestJump() {
            jump = true;
        }
    }
}
