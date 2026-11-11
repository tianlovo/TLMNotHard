package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * 实际移植纯核的离线组件重放入口，输入来自独立原版采集，不读取其 normalised 结果作为期望值
 * 不复刻 Behavior 生命周期、世界 RNG、原生导航或自然 Brain
 */
public final class FollowCoreReplayMain {

    private static final String REFERENCE_SCHEMA = "follow-core-reference-v1";
    private static final String OUTPUT_SCHEMA = "follow-core-port-replay-v1";
    private static final String NO_FAULT = "none";
    private static final String DISTANCE_FAULT = "input-distance-below-six";

    private FollowCoreReplayMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                "Usage: FollowCoreReplayMain <reference.json> <output.json> [none|input-distance-below-six]");
        }
        Path input = Paths.get(args[0])
            .toAbsolutePath()
            .normalize();
        Path output = Paths.get(args[1])
            .toAbsolutePath()
            .normalize();
        if (input.equals(output) || Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IllegalArgumentException("Reference capture and replay output must be different files");
        }
        JsonObject reference;
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            reference = object(new JsonParser().parse(reader), "reference");
        }
        JsonObject result = replay(reference, args.length == 3 ? args[2] : NO_FAULT);
        Files.createDirectories(output.getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            new GsonBuilder().serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(result, writer);
            writer.write('\n');
        }
    }

    static JsonObject replay(JsonObject reference, String fault) {
        if (!NO_FAULT.equals(fault) && !DISTANCE_FAULT.equals(fault)) {
            throw new IllegalArgumentException("Unsupported replay fault: " + fault);
        }
        if (!REFERENCE_SCHEMA.equals(string(reference, "schema"))) {
            throw new IllegalArgumentException("Unsupported reference schema");
        }
        String contractSha256 = string(reference, "contractSha256");
        if (!contractSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("contractSha256 must be a SHA256 digest");
        }
        String referenceScope = string(reference, "scope");
        JsonArray cases = array(required(reference, "cases"), "cases");
        if (cases.size() == 0) {
            throw new IllegalArgumentException("Reference cases must not be empty");
        }

        var output = new JsonObject();
        output.addProperty("schema", OUTPUT_SCHEMA);
        output.addProperty("contractSha256", contractSha256);
        output.addProperty(
            "scope",
            "FOLLOW_CORE_V1 controlled decision/effects only; no Behavior lifecycle or RNG equivalence");
        output.addProperty("referenceScope", referenceScope);
        output.addProperty("fault", fault);
        var mutations = new JsonArray();
        var rows = new JsonArray();
        Set<String> identities = new HashSet<>();
        for (JsonElement element : cases) {
            JsonObject row = object(element, "case");
            String id = string(row, "id");
            int rebuild = integer(row, "rebuild");
            if (rebuild < 0 || !identities.add(id + "#" + rebuild)) {
                throw new IllegalArgumentException("Invalid or duplicate case identity: " + id + "#" + rebuild);
            }
            var actor = new ReplayActor(row);
            if (DISTANCE_FAULT.equals(fault) && "C01_exact_start".equals(id)) {
                if (!actor.ownerResolved || actor.radius != 8 || actor.distanceSquared != 36) {
                    throw new IllegalArgumentException(
                        "Distance fault requires the captured radius-eight exact-six input");
                }
                // 仅测试副本在纯核实际消费前注入输入故障，不修改原始采集或保存后的结果
                actor.distanceSquared = 5.999 * 5.999;
                var mutation = new JsonObject();
                mutation.addProperty("id", id);
                mutation.addProperty("rebuild", rebuild);
                mutation.addProperty("field", "distanceSquared");
                mutation.addProperty("captured", 36);
                mutation.addProperty("consumed", actor.distanceSquared);
                mutations.add(mutation);
            }

            boolean started = new MaidFollowOwnerTask(0.5F, 2).tryStart(actor);
            var replay = new JsonObject();
            replay.addProperty("id", id);
            replay.addProperty("rebuild", rebuild);
            replay.add("normalised", actor.normalised(started));
            replay.add("callTrace", actor.callTrace);
            rows.add(replay);
        }
        if (DISTANCE_FAULT.equals(fault) && mutations.size() == 0) {
            throw new IllegalArgumentException("Distance fault target was not present in the capture");
        }
        output.add("inputMutations", mutations);
        output.add("cases", rows);
        return output;
    }

    private static final class ReplayActor implements FollowOwnerActor {

        final Object world = new Object();
        final ReplayOwner owner;
        final boolean ownerResolved;
        final float radius;
        final boolean home;
        final boolean canMove;
        final JsonArray teleportReturns;
        final JsonArray teleportObservations;
        final JsonArray callTrace = new JsonArray();
        final Object initialWalkIdentity;
        Memory memory;
        Object walkIdentity;
        double distanceSquared;
        int ownerQueries;
        int teleportCalls;
        int resetCalls;

        ReplayActor(JsonObject row) {
            JsonObject input = object(required(row, "input"), "input");
            JsonObject actual = object(required(input, "actual"), "input.actual");
            double actualRadius = number(actual, "radius");
            radius = (float) actualRadius;
            if (!Float.isFinite(radius) || (double) radius != actualRadius) {
                throw new IllegalArgumentException("actual.radius must represent the captured finite float exactly");
            }
            home = bool(actual, "home");
            canMove = bool(actual, "canBrainMove");
            boolean breathing = bool(actual, "breathing");
            ownerResolved = bool(actual, "ownerResolved");
            if (ownerResolved) {
                distanceSquared = number(actual, "distanceSquared");
                if (distanceSquared < 0) {
                    throw new IllegalArgumentException("actual.distanceSquared must not be negative");
                }
                owner = new ReplayOwner(
                    bool(actual, "sameLevel") ? world : new Object(),
                    bool(actual, "ownerSpectator"),
                    bool(actual, "ownerDeadOrDying"),
                    bool(actual, "ownerUnderwater"));
            } else {
                owner = null;
                requireNull(actual, "distanceSquared");
                requireNull(actual, "sameLevel");
                requireNull(actual, "ownerSpectator");
                requireNull(actual, "ownerDeadOrDying");
                requireNull(actual, "ownerUnderwater");
            }
            memory = Memory.read(object(required(row, "before"), "before"));
            if (memory.breathing != breathing) {
                throw new IllegalArgumentException("actual.breathing differs from the captured initial memory");
            }
            walkIdentity = "none".equals(memory.walkTarget()) ? null : new Object();
            initialWalkIdentity = walkIdentity;
            teleportReturns = array(required(row, "teleportReturns"), "teleportReturns");
            teleportObservations = array(required(row, "teleportObservations"), "teleportObservations");
            if (teleportReturns.size() != teleportObservations.size()) {
                throw new IllegalArgumentException("Every teleport return needs a matching native-effect observation");
            }
            for (int i = 0; i < teleportReturns.size(); i++) {
                boolean result = booleanValue(teleportReturns.get(i), "teleportReturns[" + i + "]");
                JsonObject observation = object(teleportObservations.get(i), "teleport observation");
                if (result != bool(observation, "result")) {
                    throw new IllegalArgumentException("Teleport return and observation disagree");
                }
                bool(observation, "native");
                bool(observation, "walkTargetRetained");
                Memory.read(object(required(observation, "before"), "teleport.before"));
                Memory.read(object(required(observation, "after"), "teleport.after"));
            }
        }

        @Override
        public Owner getOwner() {
            ownerQueries++;
            var call = new JsonObject();
            call.addProperty("op", "resolveOwner");
            call.addProperty("owner", ownerResolved ? "owner" : "none");
            callTrace.add(call);
            return ownerResolved ? owner : null;
        }

        @Override
        public Object getWorldIdentity() {
            return world;
        }

        @Override
        public float getRestrictRadius() {
            return radius;
        }

        @Override
        public boolean isGoingToBreath() {
            return memory.breathing;
        }

        @Override
        public boolean isHomeModeEnable() {
            return home;
        }

        @Override
        public boolean canBrainMoving() {
            return canMove;
        }

        @Override
        public boolean closerThan(Owner selectedOwner, double distance) {
            requireOwner(selectedOwner);
            return distanceSquared < distance * distance;
        }

        @Override
        public boolean ownerIsWalkTarget(Owner selectedOwner) {
            requireOwner(selectedOwner);
            return "owner".equals(memory.walkTarget());
        }

        @Override
        public boolean teleportToOwner(Owner selectedOwner) {
            requireOwner(selectedOwner);
            if (teleportCalls >= teleportObservations.size()) {
                throw new IllegalArgumentException("Pure core requested a teleport absent from the reference capture");
            }
            JsonObject observation = object(teleportObservations.get(teleportCalls), "teleport observation");
            Memory before = Memory.read(object(required(observation, "before"), "teleport.before"));
            if (!memory.json()
                .equals(before.json())) {
                throw new IllegalArgumentException("Pure core memory differs before the observed native teleport");
            }
            Memory after = Memory.read(object(required(observation, "after"), "teleport.after"));
            boolean retained = bool(observation, "walkTargetRetained");
            if (retained && !memory.walk.equals(after.walk)) {
                throw new IllegalArgumentException("Retained WALK identity has different immutable target parameters");
            }
            if (!retained) {
                walkIdentity = "none".equals(after.walkTarget()) ? null : new Object();
            }
            if (retained && "none".equals(after.walkTarget()) != (walkIdentity == null)) {
                throw new IllegalArgumentException("Retained WALK identity disagrees with target presence");
            }
            memory = after;
            boolean result = booleanValue(teleportReturns.get(teleportCalls), "teleport return");
            teleportCalls++;
            var call = new JsonObject();
            call.addProperty("op", "teleport");
            call.addProperty("result", result);
            call.addProperty("native", bool(observation, "native"));
            callTrace.add(call);
            return result;
        }

        @Override
        public void resetNavigation() {
            resetCalls++;
            var call = new JsonObject();
            call.addProperty("op", "resetNavigation");
            callTrace.add(call);
        }

        @Override
        public void setGoingToBreath(boolean goingToBreath) {
            memory.breathing = goingToBreath;
        }

        @Override
        public void eraseWalkTarget() {
            memory.walk = Memory.emptyWalk();
            walkIdentity = null;
        }

        @Override
        public void eraseTargetPos() {
            memory.targetPos = "none";
        }

        @Override
        public void setWalkAndLookTargetMemories(Owner selectedOwner, float speedModifier, int stopDistance) {
            requireOwner(selectedOwner);
            memory.walk = new JsonObject();
            memory.walk.addProperty("target", "owner");
            memory.walk.addProperty("speed", speedModifier);
            memory.walk.addProperty("closeEnough", stopDistance);
            walkIdentity = new Object();
            memory.look = "owner";
        }

        @Override
        public void stopFollowingTask() {
            // 对应原版 doStop 观察点；外层 Behavior 的状态不进入纯核 normalised 合同
        }

        private void requireOwner(Owner selectedOwner) {
            if (owner == null || selectedOwner != owner) {
                throw new IllegalArgumentException("Operation did not use the currently resolved owner");
            }
        }

        JsonObject normalised(boolean started) {
            JsonObject result = memory.json();
            result.addProperty("started", started);
            result.addProperty("ownerQueries", ownerQueries);
            result.addProperty("teleportCalls", teleportCalls);
            result.addProperty("resetCalls", resetCalls);
            result
                .addProperty("walkTargetRetained", initialWalkIdentity != null && initialWalkIdentity == walkIdentity);
            return result;
        }
    }

    private static final class ReplayOwner implements FollowOwnerActor.Owner {

        final Object world;
        final boolean spectator;
        final boolean dead;
        final boolean underwater;

        ReplayOwner(Object world, boolean spectator, boolean dead, boolean underwater) {
            this.world = world;
            this.spectator = spectator;
            this.dead = dead;
            this.underwater = underwater;
        }

        @Override
        public Object getWorldIdentity() {
            return world;
        }

        @Override
        public boolean isSpectator() {
            return spectator;
        }

        @Override
        public boolean isDeadOrDying() {
            return dead;
        }

        @Override
        public boolean isUnderWater() {
            return underwater;
        }
    }

    private static final class Memory {

        JsonObject walk;
        String look;
        String attack;
        String path;
        String targetPos;
        boolean breathing;

        static Memory read(JsonObject json) {
            var memory = new Memory();
            JsonObject walk = object(required(json, "walk"), "walk");
            String target = role(walk, "target", "none", "owner", "other");
            if ("none".equals(target)) {
                requireNull(walk, "speed");
                requireNull(walk, "closeEnough");
                memory.walk = emptyWalk();
            } else {
                double speed = number(walk, "speed");
                int closeEnough = integer(walk, "closeEnough");
                memory.walk = new JsonObject();
                memory.walk.addProperty("target", target);
                memory.walk.addProperty("speed", speed);
                memory.walk.addProperty("closeEnough", closeEnough);
            }
            memory.look = role(json, "look", "none", "owner", "other");
            memory.attack = role(json, "attack", "none", "owner", "other");
            memory.path = role(json, "path", "none", "present");
            memory.targetPos = role(json, "targetPos", "none", "owner", "other");
            memory.breathing = bool(json, "breathing");
            return memory;
        }

        static JsonObject emptyWalk() {
            var walk = new JsonObject();
            walk.addProperty("target", "none");
            walk.add("speed", JsonNull.INSTANCE);
            walk.add("closeEnough", JsonNull.INSTANCE);
            return walk;
        }

        String walkTarget() {
            return walk.get("target")
                .getAsString();
        }

        JsonObject json() {
            var json = new JsonObject();
            json.add("walk", new JsonParser().parse(walk.toString()));
            json.addProperty("look", look);
            json.addProperty("attack", attack);
            json.addProperty("path", path);
            json.addProperty("targetPos", targetPos);
            json.addProperty("breathing", breathing);
            return json;
        }
    }

    private static JsonElement required(JsonObject object, String key) {
        if (!object.has(key)) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return object.get(key);
    }

    private static void requireNull(JsonObject object, String key) {
        if (!required(object, key).isJsonNull()) {
            throw new IllegalArgumentException("Field must explicitly be null: " + key);
        }
    }

    private static JsonObject object(JsonElement element, String label) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected object: " + label);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonElement element, String label) {
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("Expected array: " + label);
        }
        return element.getAsJsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = required(object, key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive()
            .isString()
            || element.getAsString()
                .isEmpty()) {
            throw new IllegalArgumentException("Expected nonempty string: " + key);
        }
        return element.getAsString();
    }

    private static String role(JsonObject object, String key, String... choices) {
        String value = string(object, key);
        for (String choice : choices) {
            if (choice.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported role for " + key + ": " + value);
    }

    private static boolean bool(JsonObject object, String key) {
        return booleanValue(required(object, key), key);
    }

    private static boolean booleanValue(JsonElement element, String label) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive()
            .isBoolean()) {
            throw new IllegalArgumentException("Expected boolean: " + label);
        }
        return element.getAsBoolean();
    }

    private static double number(JsonObject object, String key) {
        JsonElement element = required(object, key);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Expected number: " + key);
        }
        double number = primitive.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("Expected finite number: " + key);
        }
        return number;
    }

    private static int integer(JsonObject object, String key) {
        double number = number(object, key);
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected integer: " + key);
        }
        return (int) number;
    }
}
