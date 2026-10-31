package com.tianluoqaq.tlmnh.common.ai.faithful;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;

/** 实际移植移动sink的受控重放，不读取原版各步before/after作为执行答案 */
public final class MoveCoreReplayMain {

    private static final String FAULT = "deadline-equality-plus-one";
    private static final String INPUT_SCHEMA = "move-sink-input-observations-v1";

    private MoveCoreReplayMain() {}

    public static void main(String[] args) throws Exception {
        require(
            args.length == 2 || args.length == 3,
            "Usage: MoveCoreReplayMain <reference.json> <output.json> [none|deadline-equality-plus-one]");
        Path input = Paths.get(args[0])
            .toAbsolutePath()
            .normalize();
        Path output = Paths.get(args[1])
            .toAbsolutePath()
            .normalize();
        require(
            !input.equals(output) && !(Files.exists(output) && Files.isSameFile(input, output)),
            "Output must not replace reference capture");
        JsonObject reference;
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            reference = parseStrict(reader);
        }
        JsonObject result = replay(reference, args.length == 3 ? args[2] : "none");
        Files.createDirectories(output.getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            new GsonBuilder().serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(result, writer);
            writer.write('\n');
        }
    }

    static JsonObject parseStrict(Reader input) throws IOException {
        var reader = new JsonReader(input);
        reader.setLenient(false);
        JsonObject result = obj(readValue(reader), "reference");
        require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON data");
        return result;
    }

    private static JsonElement readValue(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!object.has(name), "Duplicate JSON field: " + name);
                    object.add(name, readValue(reader));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader));
                reader.endArray();
                return array;
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                var number = new BigDecimal(reader.nextString());
                require(Double.isFinite(number.doubleValue()), "Non-finite JSON number");
                return new JsonPrimitive(number);
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IllegalArgumentException("Unsupported JSON token");
        }
    }

    static JsonObject replay(JsonObject reference, String fault) {
        require("move-sink-reference-v1".equals(text(reference, "schema")), "Unknown move reference schema");
        require(
            INPUT_SCHEMA.equals(text(reference, "inputObservationSchema")),
            "Missing reviewed actual-input observations");
        require("none".equals(fault) || FAULT.equals(fault), "Unknown move replay fault");
        String digest = text(reference, "contractSha256");
        require(digest.matches("[0-9a-fA-F]{64}"), "Invalid contract digest");
        var output = new JsonObject();
        output.addProperty("schema", "move-sink-port-replay-v1");
        output.addProperty("contractSha256", digest);
        output.addProperty("inputObservationSchema", INPUT_SCHEMA);
        output.addProperty(
            "scope",
            "controlled actual port sink/state/world RNG; observed navigation/fallback dependencies; no natural navigation or Brain admission");
        output.addProperty("fault", fault);
        var rows = new JsonArray();
        var mutations = new JsonArray();
        var identities = new HashSet<String>();
        JsonArray cases = array(field(reference, "cases"), "cases");
        require(cases.size() > 0, "Empty move reference");
        for (JsonElement value : cases) {
            JsonObject row = obj(value, "case");
            String id = text(row, "id");
            int rebuild = integer(row, "rebuild");
            require(rebuild >= 0 && identities.add(id + "#" + rebuild), "Duplicate or invalid case/rebuild");
            var state = new FollowMoveState();
            var actor = new ReplayActor(row);
            // 初态只校验一次；不从后续oracle状态回填driver或memory
            require(
                sink(state, actor).equals(field(obj(field(row, "initialState"), "initialState"), "sink")),
                "Unsupported non-default sink initial state");
            var driver = new FollowCoreMoveDriver();
            var steps = new JsonArray();
            JsonArray inputSteps = array(field(row, "steps"), "steps");
            require(inputSteps.size() > 0, "Empty move step sequence");
            for (int index = 0; index < inputSteps.size(); index++) {
                JsonObject step = obj(inputSteps.get(index), "step");
                require(integer(step, "index") == index, "Missing, duplicate or reordered step index");
                String operation = text(step, "operation");
                long methodTime = longNumber(step, "methodTime");
                long consumedTime = methodTime;
                actor.prepare(step);
                if (FAULT.equals(fault) && "S01_duration_min".equals(id) && index == 1) {
                    require(
                        "tickOrStop".equals(operation) && methodTime == 250
                            && state.getEndTimestamp() == 250
                            && state.getStatus() == FollowMoveState.Status.RUNNING,
                        "Deadline fault target is not the captured equality boundary");
                    consumedTime++;
                    var mutation = new JsonObject();
                    mutation.addProperty("id", id);
                    mutation.addProperty("rebuild", rebuild);
                    mutation.addProperty("index", index);
                    mutation.addProperty("field", "methodTime");
                    mutation.addProperty("captured", methodTime);
                    mutation.addProperty("consumed", consumedTime);
                    mutations.add(mutation);
                }
                JsonObject before = actor.snapshot(state);
                Boolean returned;
                switch (operation) {
                    case "tryStart":
                        returned = driver.tryStart(actor, state, consumedTime);
                        break;
                    case "tickOrStop":
                        driver.tickOrStop(actor, state, consumedTime);
                        returned = null;
                        break;
                    case "doStop":
                        driver.doStop(actor, state, consumedTime);
                        returned = null;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown move operation: " + operation);
                }
                actor.verifyAfterInputs();
                var result = new JsonObject();
                result.addProperty("index", index);
                result.addProperty("operation", operation);
                result.addProperty("methodTime", methodTime);
                result.addProperty("consumedMethodTime", consumedTime);
                result.addProperty("worldGameTime", actor.worldTime);
                result.add("before", before);
                result.add("after", actor.snapshot(state));
                result.addProperty("methodReturn", returned);
                result.add("callTrace", actor.trace);
                steps.add(result);
            }
            var result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("rebuild", rebuild);
            result.add("steps", steps);
            rows.add(result);
        }
        require(!FAULT.equals(fault) || mutations.size() > 0, "Deadline fault target was absent");
        output.add("inputMutations", mutations);
        output.add("cases", rows);
        return output;
    }

    private static final class ReplayPath implements FollowMoveActor.Path {

        final String id;
        final boolean reachable;

        ReplayPath(String id, JsonObject descriptor) {
            this.id = id;
            reachable = bool(descriptor, "canReach");
            block(field(descriptor, "target"));
            for (JsonElement node : array(field(descriptor, "nodes"), "nodes")) block(node);
        }

        @Override
        public Object getIdentity() {
            return this;
        }

        @Override
        public boolean canReach() {
            return reachable;
        }
    }

    private static final class ReplayActor implements FollowMoveActor {

        final Map<String, ReplayPath> paths = new LinkedHashMap<>();
        final JsonObject configuration;
        boolean walkRegistered, pathRegistered, cantRegistered;
        ReplayWalk walk;
        ReplayPath pathMemory, navPath;
        Long cantSince;
        BlockPos maidBlock, targetBlock;
        boolean targetSpectator, done, stuck;
        long worldTime;
        JsonArray trace;
        final List<JsonObject> creates = new ArrayList<>(), randoms = new ArrayList<>();
        final Map<Integer, JsonObject> callbacks = new HashMap<>();
        JsonArray moves;
        JsonObject fallback;
        JsonObject afterInputs;
        int createIndex, randomIndex, moveIndex;

        ReplayActor(JsonObject row) {
            configuration = copy(obj(field(obj(field(row, "input"), "input"), "initial"), "initial"));
            JsonObject table = obj(field(row, "paths"), "paths");
            for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
                require(
                    !entry.getKey()
                        .isEmpty(),
                    "Empty path identity");
                paths.put(entry.getKey(), new ReplayPath(entry.getKey(), obj(entry.getValue(), "path")));
            }
            JsonObject initialInputs = obj(field(row, "initialInputObservation"), "initialInputObservation");
            validateInputs(initialInputs);
            maidBlock = block(field(initialInputs, "maidBlock"));
            targetBlock = block(field(initialInputs, "targetEntityBlock"));
            targetSpectator = bool(initialInputs, "targetSpectator");
            require(
                point(maidBlock).equals(field(row, "initialMaidBlock"))
                    && point(targetBlock).equals(field(row, "initialTargetBlock"))
                    && targetSpectator == bool(row, "initialTargetSpectator"),
                "Initial aliases differ from actual input snapshot");
            JsonObject initial = obj(field(row, "initialState"), "initialState");
            JsonObject memory = obj(field(initial, "memory"), "memory");
            JsonObject registered = obj(field(memory, "registered"), "registered");
            walkRegistered = bool(registered, "walk");
            pathRegistered = bool(registered, "path");
            cantRegistered = bool(registered, "cantReachSince");
            pathMemory = path(field(memory, "path"));
            cantSince = nullableLong(field(memory, "cantReachSince"), "cantReachSince");
            JsonElement initialWalk = field(memory, "walk");
            if (!initialWalk.isJsonNull()) {
                JsonObject actualWalk = obj(initialWalk, "walk");
                JsonObject initialSpec = obj(field(configuration, "walk"), "walk input");
                walk = new ReplayWalk(
                    text(initialSpec, "tracker"),
                    block(field(actualWalk, "block")),
                    floatNumber(actualWalk, "speed"),
                    integer(actualWalk, "closeEnough"));
                require(
                    walk.isEntityTrackerSpectator() == bool(actualWalk, "entityTrackerSpectator"),
                    "Initial tracker type does not match actual spectator semantics");
                require(
                    point(walk.currentBlockPosition()).equals(field(actualWalk, "block")),
                    "Initial target block differs from actual tracked position");
            }
            require(walkRegistered || walk == null, "Unregistered WALK has a value");
            require(pathRegistered || pathMemory == null, "Unregistered PATH has a value");
            require(cantRegistered || cantSince == null, "Unregistered CANT_REACH has a value");
            applyNavigation(obj(field(initial, "navigation"), "navigation"), true);
        }

        void prepare(JsonObject step) {
            trace = new JsonArray();
            creates.clear();
            randoms.clear();
            callbacks.clear();
            createIndex = randomIndex = moveIndex = 0;
            worldTime = longNumber(step, "worldGameTime");
            JsonObject input = obj(field(step, "input"), "step input");
            patch(obj(field(input, "externalPatch"), "externalPatch"));
            JsonObject beforeInputs = obj(field(step, "inputBeforeInvocation"), "inputBeforeInvocation");
            afterInputs = obj(field(step, "inputAfterInvocation"), "inputAfterInvocation");
            validateInputs(beforeInputs);
            validateInputs(afterInputs);
            verifyInputs(beforeInputs);
            JsonObject invocation = obj(field(step, "methodInvocation"), "methodInvocation");
            require(
                text(step, "operation").equals(text(invocation, "operation"))
                    && longNumber(step, "methodTime") == longNumber(invocation, "methodTime")
                    && worldTime == longNumber(invocation, "worldGameTime")
                    && bool(invocation, "entityIsFixtureMaid")
                    && bool(invocation, "worldIsFixtureLevel"),
                "Actual invocation differs from captured step arguments");
            for (JsonElement item : array(field(step, "callTrace"), "callTrace")) {
                JsonObject call = obj(item, "call");
                switch (text(call, "op")) {
                    case "createPath":
                        text(call, "entry");
                        block(field(call, "target"));
                        integer(call, "accuracy");
                        path(field(call, "resultPath"));
                        creates.add(call);
                        break;
                    case "worldNextInt":
                        integer(call, "bound");
                        integer(call, "value");
                        randoms.add(call);
                        break;
                    case "moveTo":
                    case "stopNavigation":
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown captured dependency operation");
                }
            }
            moves = array(field(step, "moveObservations"), "moveObservations");
            for (JsonElement item : moves) {
                JsonObject move = obj(item, "move observation");
                path(field(move, "path"));
                number(move, "speed");
                bool(move, "result");
                validateNavigation(obj(field(move, "before"), "move before"));
                validateNavigation(obj(field(move, "after"), "move after"));
            }
            for (JsonElement item : array(field(step, "callbackObservations"), "callbacks")) {
                JsonObject callback = obj(item, "callback");
                int index = integer(callback, "createIndex");
                require(
                    index >= 0 && index < creates.size() && callbacks.put(index, callback) == null,
                    "Duplicate or invalid create callback");
                block(field(callback, "from"));
                block(field(callback, "to"));
                block(field(callback, "actualAfterBlock"));
            }
            fallback = obj(field(step, "fallbackObservation"), "fallbackObservation");
            bool(fallback, "invoked");
            require(!bool(fallback, "directVec3Observed"), "Unreviewed direct fallback vector contract");
            if (!field(fallback, "queryBlock").isJsonNull()) block(field(fallback, "queryBlock"));
        }

        private void patch(JsonObject patch) {
            Set<String> allowed = new HashSet<>(
                Arrays.asList(
                    "maidBlock",
                    "targetBlock",
                    "targetSpectator",
                    "registered",
                    "walk",
                    "pathMemory",
                    "cantReachSince",
                    "navigation",
                    "stableDestination",
                    "malus",
                    "scoreMode",
                    "onCreateMaidBlock",
                    "createReplies"));
            for (Map.Entry<String, JsonElement> entry : patch.entrySet())
                require(allowed.contains(entry.getKey()), "Unknown external patch field: " + entry.getKey());
            merge(configuration, patch);
            if (patch.has("maidBlock")) maidBlock = block(field(patch, "maidBlock"));
            if (patch.has("targetBlock")) targetBlock = block(field(patch, "targetBlock"));
            if (patch.has("targetSpectator")) targetSpectator = bool(patch, "targetSpectator");
            if (patch.has("registered")) {
                JsonObject registered = obj(field(configuration, "registered"), "registered patch");
                walkRegistered = bool(registered, "walk");
                if (!walkRegistered) walk = null;
                pathRegistered = bool(registered, "path");
                if (!pathRegistered) pathMemory = null;
                cantRegistered = bool(registered, "cantReachSince");
                if (!cantRegistered) cantSince = null;
            }
            if (patch.has("walk")) {
                JsonObject spec = obj(field(configuration, "walk"), "walk patch");
                walk = bool(spec, "present")
                    ? new ReplayWalk(
                        text(spec, "tracker"),
                        targetBlock,
                        floatNumber(spec, "speed"),
                        integer(spec, "closeEnough"))
                    : null;
                if (!walkRegistered) walk = null;
            }
            if (patch.has("pathMemory")) pathMemory = pathRegistered ? path(field(patch, "pathMemory")) : null;
            if (patch.has("cantReachSince"))
                cantSince = cantRegistered ? nullableLong(field(patch, "cantReachSince"), "cantReachSince") : null;
            if (patch.has("navigation")) applyNavigation(obj(field(patch, "navigation"), "navigation patch"), false);
            if (patch.has("stableDestination")) bool(patch, "stableDestination");
            if (patch.has("malus")) number(patch, "malus");
            if (patch.has("scoreMode")) text(patch, "scoreMode");
            if (patch.has("onCreateMaidBlock") && !field(patch, "onCreateMaidBlock").isJsonNull())
                block(field(patch, "onCreateMaidBlock"));
            if (patch.has("createReplies"))
                for (JsonElement reply : array(field(patch, "createReplies"), "createReplies")) path(reply);
        }

        private void validateNavigation(JsonObject navigation) {
            path(field(navigation, "path"));
            bool(navigation, "done");
            bool(navigation, "stuck");
        }

        private void applyNavigation(JsonObject navigation, boolean complete) {
            if (complete) validateNavigation(navigation);
            if (navigation.has("path")) navPath = path(field(navigation, "path"));
            if (navigation.has("done")) done = bool(navigation, "done");
            if (navigation.has("stuck")) stuck = bool(navigation, "stuck");
            if (navigation.has("moveResult")) bool(navigation, "moveResult");
        }

        private ReplayPath path(JsonElement id) {
            if (id.isJsonNull()) return null;
            String name = stringValue(id, "path identity");
            require(paths.containsKey(name), "Unknown path identity: " + name);
            return paths.get(name);
        }

        private JsonElement pathId(FollowMoveActor.Path path) {
            return path == null ? JsonNull.INSTANCE : new JsonPrimitive(((ReplayPath) path).id);
        }

        private JsonObject navigation() {
            var result = new JsonObject();
            result.add("path", pathId(navPath));
            result.addProperty("done", done);
            result.addProperty("stuck", stuck);
            return result;
        }

        JsonObject snapshot(FollowMoveState state) {
            var registered = new JsonObject();
            registered.addProperty("walk", walkRegistered);
            registered.addProperty("path", pathRegistered);
            registered.addProperty("cantReachSince", cantRegistered);
            var memory = new JsonObject();
            memory.add("registered", registered);
            memory.add("walk", walk == null ? JsonNull.INSTANCE : walk.json());
            memory.add("path", pathId(pathMemory));
            memory.addProperty("cantReachSince", cantSince);
            var result = new JsonObject();
            result.add("memory", memory);
            result.add("sink", sink(state, this));
            result.add("navigation", navigation());
            return result;
        }

        @Override
        public boolean isWalkTargetRegistered() {
            return walkRegistered;
        }

        @Override
        public boolean isPathRegistered() {
            return pathRegistered;
        }

        @Override
        public boolean isCantReachSinceRegistered() {
            return cantRegistered;
        }

        @Override
        public WalkTarget getWalkTarget() {
            return walk;
        }

        @Override
        public FollowMoveActor.Path getPathMemory() {
            return pathMemory;
        }

        @Override
        public Long getCantReachSince() {
            return cantSince;
        }

        @Override
        public void eraseWalkTarget() {
            walk = null;
        }

        @Override
        public void setPathMemory(FollowMoveActor.Path path) {
            if (pathRegistered) pathMemory = (ReplayPath) path;
        }

        @Override
        public void eraseCantReachSince() {
            cantSince = null;
        }

        @Override
        public void setCantReachSince(long gameTime) {
            if (cantRegistered) cantSince = gameTime;
        }

        @Override
        public BlockPos blockPosition() {
            return maidBlock;
        }

        @Override
        public long worldGameTime() {
            return worldTime;
        }

        @Override
        public int nextWorldInt(int bound) {
            require(randomIndex < randoms.size(), "Unexpected world RNG call");
            JsonObject observed = randoms.get(randomIndex++);
            require(integer(observed, "bound") == bound, "World RNG bound differs from observation");
            int value = integer(observed, "value");
            require(value >= 0 && value < bound, "Invalid observed world RNG value");
            var call = new JsonObject();
            call.addProperty("op", "worldNextInt");
            call.addProperty("bound", bound);
            call.addProperty("value", value);
            trace.add(call);
            return value;
        }

        @Override
        public FollowMoveActor.Path createPath(BlockPos target, int accuracy) {
            return create(target, accuracy, "block");
        }

        @Override
        public FollowMoveActor.Path createPath(Vec3 target, int accuracy) {
            return create(
                new BlockPos(floor(target.x), floor(target.y), floor(target.z)),
                accuracy,
                "coordinate_wrapper");
        }

        private FollowMoveActor.Path create(BlockPos target, int accuracy, String entry) {
            require(createIndex < creates.size(), "Unexpected path creation");
            JsonObject observed = creates.get(createIndex);
            require(
                entry.equals(text(observed, "entry")) && point(target).equals(field(observed, "target"))
                    && accuracy == integer(observed, "accuracy"),
                "Path query differs from captured dependency");
            ReplayPath result = path(field(observed, "resultPath"));
            var call = new JsonObject();
            call.addProperty("op", "createPath");
            call.addProperty("entry", entry);
            call.add("target", point(target));
            call.addProperty("accuracy", accuracy);
            call.add("resultPath", pathId(result));
            trace.add(call);
            JsonObject callback = callbacks.get(createIndex++);
            if (callback != null) {
                require(point(maidBlock).equals(field(callback, "from")), "Create callback initial position differs");
                require(
                    field(callback, "to").equals(field(callback, "actualAfterBlock")),
                    "Actual callback destination differs from its request");
                maidBlock = block(field(callback, "actualAfterBlock"));
            }
            return result;
        }

        @Override
        public Vec3 findFallbackTowards(int horizontal, int vertical, Vec3 target, double angle) {
            require(
                bool(fallback, "invoked") && horizontal == 10 && vertical == 7 && angle == 1.5707963705062866,
                "Fallback request is not the observed modern dependency");
            require(createIndex > 0, "Fallback requested before direct path query");
            BlockPos originalTarget = block(field(creates.get(createIndex - 1), "target"));
            require(
                target.x == originalTarget.x + 0.5 && target.y == originalTarget.y
                    && target.z == originalTarget.z + 0.5,
                "Fallback target is not the original bottom center");
            JsonElement query = field(fallback, "queryBlock");
            if (query.isJsonNull()) return null;
            BlockPos position = block(query);
            // 原版未直接观察Vec3，只按真实第二query整格归一化；不证明浮点采样算法
            return new Vec3(position.x + 0.5, position.y, position.z + 0.5);
        }

        @Override
        public FollowMoveActor.Path navigationPath() {
            return navPath;
        }

        @Override
        public boolean navigationIsDone() {
            return done;
        }

        @Override
        public boolean navigationIsStuck() {
            return stuck;
        }

        @Override
        public boolean moveTo(FollowMoveActor.Path path, double speed) {
            require(moveIndex < moves.size(), "Unexpected navigation installation");
            JsonObject observation = obj(moves.get(moveIndex++), "move observation");
            require(
                pathId(path).equals(field(observation, "path")) && speed == number(observation, "speed")
                    && navigation().equals(field(observation, "before")),
                "Navigation request differs from observed dependency");
            boolean result = bool(observation, "result");
            applyNavigation(obj(field(observation, "after"), "move after"), true);
            var call = new JsonObject();
            call.addProperty("op", "moveTo");
            call.add("path", pathId(path));
            call.addProperty("speed", speed);
            call.addProperty("result", result);
            trace.add(call);
            return result;
        }

        @Override
        public void stopNavigation() {
            // 独立collector的受控stop明确只改这两个字段，额外故障stop也走此实际依赖合同
            navPath = null;
            done = true;
            var call = new JsonObject();
            call.addProperty("op", "stopNavigation");
            trace.add(call);
        }

        void verifyAfterInputs() {
            verifyInputs(afterInputs);
        }

        private void verifyInputs(JsonObject inputs) {
            require(
                point(maidBlock).equals(field(inputs, "maidBlock"))
                    && point(targetBlock).equals(field(inputs, "targetEntityBlock"))
                    && targetSpectator == bool(inputs, "targetSpectator")
                    && worldTime == longNumber(inputs, "worldGameTime"),
                "Actual entity/clock inputs differ from replay state");
            require(
                center(maidBlock).equals(field(inputs, "maidPosition"))
                    && center(targetBlock).equals(field(inputs, "targetEntityPosition")),
                "Actual position is outside the controlled centered-block contract");
        }

        private final class ReplayWalk implements WalkTarget {

            final boolean entity;
            final BlockPos fixedBlock;
            final float speed;
            final int closeEnough;

            ReplayWalk(String tracker, BlockPos fixedBlock, float speed, int closeEnough) {
                require("entity".equals(tracker) || "block".equals(tracker), "Unknown tracker type");
                entity = "entity".equals(tracker);
                this.fixedBlock = fixedBlock;
                this.speed = speed;
                this.closeEnough = closeEnough;
            }

            @Override
            public BlockPos currentBlockPosition() {
                return entity ? targetBlock : fixedBlock;
            }

            @Override
            public float getSpeedModifier() {
                return speed;
            }

            @Override
            public int getCloseEnoughDistance() {
                return closeEnough;
            }

            @Override
            public boolean isEntityTrackerSpectator() {
                return entity && targetSpectator;
            }

            JsonObject json() {
                var result = new JsonObject();
                result.add("block", point(currentBlockPosition()));
                result.addProperty("speed", speed);
                result.addProperty("closeEnough", closeEnough);
                result.addProperty("entityTrackerSpectator", isEntityTrackerSpectator());
                return result;
            }
        }
    }

    private static JsonObject sink(FollowMoveState state, ReplayActor actor) {
        var result = new JsonObject();
        result.addProperty(
            "status",
            state.getStatus()
                .name());
        result.addProperty("remainingCooldown", state.getRemainingCooldown());
        result.add("path", actor.pathId(state.getPath()));
        result.add(
            "lastTargetPos",
            state.getLastTargetPos() == null ? JsonNull.INSTANCE : point(state.getLastTargetPos()));
        result.addProperty("speedModifier", state.getSpeedModifier());
        result.addProperty("endTimestamp", state.getEndTimestamp());
        return result;
    }

    private static JsonObject copy(JsonObject object) {
        return new JsonParser().parse(object.toString())
            .getAsJsonObject();
    }

    private static void validateInputs(JsonObject inputs) {
        block(field(inputs, "maidBlock"));
        block(field(inputs, "targetEntityBlock"));
        finiteVector(field(inputs, "maidPosition"));
        finiteVector(field(inputs, "targetEntityPosition"));
        bool(inputs, "targetSpectator");
        longNumber(inputs, "worldGameTime");
        JsonElement value = field(inputs, "walkTracker");
        if (!value.isJsonNull()) {
            JsonObject tracker = obj(value, "walkTracker");
            String type = text(tracker, "type");
            require("entity".equals(type) || "block".equals(type), "Unsupported actual tracker type");
            block(field(tracker, "block"));
            finiteVector(field(tracker, "currentPosition"));
            if ("entity".equals(type)) {
                require(
                    bool(tracker, "entityIsConfiguredTarget"),
                    "Tracker does not reference the configured actual target");
                java.util.UUID.fromString(text(tracker, "entityUuid"));
                text(tracker, "entityRuntimeClass");
                bool(tracker, "entitySpectator");
            } else {
                for (String key : new String[] { "entityIsConfiguredTarget", "entityUuid", "entityRuntimeClass",
                    "entitySpectator" }) {
                    require(field(tracker, key).isJsonNull(), "Block tracker has entity identity data");
                }
            }
        }
    }

    private static void finiteVector(JsonElement value) {
        JsonArray vector = array(value, "position");
        require(vector.size() == 3, "Position needs three coordinates");
        for (JsonElement coordinate : vector) decimal(coordinate, "position coordinate");
    }

    private static JsonArray center(BlockPos block) {
        var result = new JsonArray();
        result.add(new JsonPrimitive(block.x + 0.5));
        result.add(new JsonPrimitive(block.y));
        result.add(new JsonPrimitive(block.z + 0.5));
        return result;
    }

    private static void merge(JsonObject destination, JsonObject patch) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonObject() && destination.has(key)
                && destination.get(key)
                    .isJsonObject()) {
                merge(destination.getAsJsonObject(key), value.getAsJsonObject());
            } else {
                destination.add(key, new JsonParser().parse(value.toString()));
            }
        }
    }

    private static JsonArray point(BlockPos block) {
        var array = new JsonArray();
        array.add(new JsonPrimitive(block.x));
        array.add(new JsonPrimitive(block.y));
        array.add(new JsonPrimitive(block.z));
        return array;
    }

    private static BlockPos block(JsonElement value) {
        JsonArray coordinates = array(value, "block");
        require(coordinates.size() == 3, "Block needs three coordinates");
        return new BlockPos(
            intValue(coordinates.get(0), "x"),
            intValue(coordinates.get(1), "y"),
            intValue(coordinates.get(2), "z"));
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static JsonElement field(JsonObject object, String key) {
        require(object.has(key), "Missing required move field: " + key);
        return object.get(key);
    }

    private static JsonObject obj(JsonElement value, String label) {
        require(value.isJsonObject(), "Expected object: " + label);
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String label) {
        require(value.isJsonArray(), "Expected array: " + label);
        return value.getAsJsonArray();
    }

    private static String text(JsonObject object, String key) {
        return stringValue(field(object, key), key);
    }

    private static String stringValue(JsonElement value, String label) {
        require(
            value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isString()
                && !value.getAsString()
                    .isEmpty(),
            "Expected nonempty string: " + label);
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = field(object, key);
        require(
            value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isBoolean(),
            "Expected boolean: " + key);
        return value.getAsBoolean();
    }

    private static BigDecimal decimal(JsonElement value, String label) {
        require(
            value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isNumber(),
            "Expected number: " + label);
        BigDecimal number = value.getAsBigDecimal();
        require(Double.isFinite(number.doubleValue()), "Expected finite number: " + label);
        return number;
    }

    private static double number(JsonObject object, String key) {
        return decimal(field(object, key), key).doubleValue();
    }

    private static float floatNumber(JsonObject object, String key) {
        float value = decimal(field(object, key), key).floatValue();
        require(Float.isFinite(value), "Expected finite float: " + key);
        return value;
    }

    private static int intValue(JsonElement value, String label) {
        return decimal(value, label).intValueExact();
    }

    private static int integer(JsonObject object, String key) {
        return intValue(field(object, key), key);
    }

    private static long longNumber(JsonObject object, String key) {
        return decimal(field(object, key), key).longValueExact();
    }

    private static Long nullableLong(JsonElement value, String label) {
        return value.isJsonNull() ? null : decimal(value, label).longValueExact();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
