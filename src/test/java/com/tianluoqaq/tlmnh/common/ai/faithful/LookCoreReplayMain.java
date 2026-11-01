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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * 独立LOOK策略组件重放，执行真实FollowCoreLookDriver
 * 原版逐步before/after和methodReturn不作为执行输入，组合Brain和helper旋转不在范围内
 */
public final class LookCoreReplayMain {

    private static final String FAULT = "deadline-equality-plus-one";
    private static final String INPUT_SCHEMA = "look-component-input-observations-v1";
    private static final String TRACKER_CLASS = "net.minecraft.world.entity.ai.behavior.EntityTracker";

    private LookCoreReplayMain() {}

    public static void main(String[] args) throws Exception {
        require(
            args.length == 2 || args.length == 3,
            "Usage: LookCoreReplayMain <reference.json> <output.json> [none|deadline-equality-plus-one]");
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

    // 与现有MoveCoreReplayMain同样拒绝重复键；仅使用native Gson 2.2.4已有API
    static JsonObject parseStrict(Reader input) throws IOException {
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);
        JsonObject result = object(readValue(reader), "reference");
        require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON data");
        return result;
    }

    private static JsonElement readValue(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!object.has(name), "Duplicate JSON field: " + name);
                    object.add(name, readValue(reader));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader));
                reader.endArray();
                return array;
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                BigDecimal number = new BigDecimal(reader.nextString());
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
        require("look-reference-capture-v1".equals(text(reference, "schema")), "Unknown look reference schema");
        require(INPUT_SCHEMA.equals(text(reference, "inputObservationSchema")), "Missing reviewed input observations");
        require("none".equals(text(reference, "fault")), "Replay input must be the unmodified normal reference");
        require(
            bool(reference, "complete") && !bool(reference, "naturalBrainVerified")
                && !bool(reference, "nativeHelperTicked")
                && !bool(reference, "nativeNavigationVerified"),
            "Invalid component scope");
        require("none".equals(fault) || FAULT.equals(fault), "Unknown look replay fault");
        String digest = text(reference, "contractSha256");
        require(digest.matches("[0-9a-f]{64}"), "Invalid contract SHA256");
        JsonArray declared = array(field(reference, "contracts"), "contracts");
        require(
            declared.size() == 2 && "LOOK_SINK_CORE_V1".equals(string(declared.get(0), "contract"))
                && "FOLLOW_MOVE_LOOK_COMPONENT_V2".equals(string(declared.get(1), "contract")),
            "Unknown component contract set");
        JsonObject result = new JsonObject();
        result.addProperty("schema", "look-sink-port-replay-v1");
        result.addProperty("contract", "LOOK_SINK_CORE_V1");
        result.addProperty("contractSha256", digest);
        result.addProperty("inputObservationSchema", INPUT_SCHEMA);
        result.addProperty(
            "scope",
            "actual port LOOK policy/status/deadline/memory/request/shared-world-integers; controlled visibility; no helper tick, combined Brain, natural AI or complete F/N admission");
        result.addProperty("driverClass", FollowCoreLookDriver.class.getName());
        result.addProperty("fault", fault);
        result.addProperty("aiCapabilityEvaluated", false);
        result.addProperty("combinedV2Implemented", false);
        JsonArray mutations = new JsonArray(), output = new JsonArray(), excluded = new JsonArray();
        Set<String> identities = new HashSet<String>(), lookIds = new HashSet<String>(),
            combinedIds = new HashSet<String>();
        int inputRows = 0;
        for (JsonElement value : array(field(reference, "cases"), "cases")) {
            JsonObject row = object(value, "case");
            String id = text(row, "id");
            int rebuild = integer(row, "rebuild");
            require(
                rebuild >= 0 && rebuild < 3 && identities.add(id + "#" + rebuild),
                "Duplicate or invalid case/rebuild");
            JsonObject spec = object(field(row, "input"), "input");
            require(id.equals(text(spec, "id")), "Declared and observed case id differ");
            inputRows++;
            String kind = text(spec, "kind");
            if ("look".equals(kind)) {
                require("LOOK_SINK_CORE_V1".equals(text(row, "contract")), "LOOK row contract differs");
                lookIds.add(id);
                output.add(replayRow(row, fault, mutations));
            } else {
                require(
                    "combined".equals(kind) && "FOLLOW_MOVE_LOOK_COMPONENT_V2".equals(text(row, "contract")),
                    "Unknown row kind");
                combinedIds.add(id);
                JsonObject omitted = new JsonObject();
                omitted.addProperty("id", id);
                omitted.addProperty("rebuild", rebuild);
                omitted.addProperty("reason", "FOLLOW_MOVE_LOOK_COMPONENT_V2_NOT_IMPLEMENTED");
                excluded.add(omitted);
            }
        }
        require(
            lookIds.equals(
                new HashSet<String>(
                    Arrays.asList(
                        "L01_min_duration",
                        "L02_max_duration",
                        "L03_absent",
                        "L04_invisible_present",
                        "L05_remove_running",
                        "L06_replace_position",
                        "L07_feet_tracker")))
                && output.size() == 21
                && combinedIds
                    .equals(new HashSet<String>(Arrays.asList("C01_empty_to_follow", "C02_look_expires_walk_retained")))
                && excluded.size() == 6
                && inputRows == 27,
            "Incomplete reviewed case set");
        require("none".equals(fault) || mutations.size() == 3, "Fault boundary missing from capture");
        result.add("inputMutations", mutations);
        result.add("cases", output);
        result.add("excluded", excluded);
        return result;
    }

    static JsonObject replayRow(JsonObject row, String fault, JsonArray mutations) {
        require("none".equals(fault) || FAULT.equals(fault), "Unknown look replay fault");
        require(
            bool(row, "complete") && bool(row, "cleanupComplete")
                && bool(row, "worldRandomRestored")
                && bool(row, "worldClockRestored"),
            "Incomplete reference row");
        String id = text(row, "id");
        JsonObject spec = object(field(row, "input"), "input");
        JsonArray capturedSteps = array(field(row, "steps"), "steps");
        JsonArray plannedSteps = array(field(spec, "steps"), "input.steps");
        require(
            capturedSteps.size() > 0 && capturedSteps.size() == plannedSteps.size() && capturedSteps.size() <= 16,
            "Missing or extra method steps");
        ReplayActor actor = new ReplayActor(row);
        require(
            longNumber(actor.previousObserved, "worldGameTime")
                == longNumber(object(plannedSteps.get(0), "first planned step"), "methodTime"),
            "Initial actual world clock differs");
        FollowCoreLookDriver driver = new FollowCoreLookDriver();
        FollowCoreLookDriver.State state = new FollowCoreLookDriver.State();
        JsonObject initial = object(field(row, "initialState"), "initialState");
        validateInitial(initial);
        JsonObject initialLook = object(field(object(field(initial, "tasks"), "tasks"), "look"), "tasks.look");
        require(
            "STOPPED".equals(text(initialLook, "status")) && longNumber(initialLook, "endTimestamp") == 0
                && integer(initial, "worldIntegerIndex") == 0,
            "Unsupported non-default LOOK driver initial state");
        require(
            field(object(field(initial, "memory"), "memory"), "look").equals(actor.lookJson()),
            "Initial LOOK mapping differs");
        JsonArray output = new JsonArray();
        for (int index = 0; index < capturedSteps.size(); index++) {
            JsonObject step = object(capturedSteps.get(index), "step"),
                planned = object(plannedSteps.get(index), "planned step");
            require(
                integer(step, "index") == index && bool(step, "complete"),
                "Incomplete, duplicate or reordered method step");
            String operation = text(step, "operation");
            require(operation.equals(text(planned, "operation")), "Actual operation differs from declared input");
            long actualTime = longNumber(step, "actualMethodTime");
            require(
                longNumber(step, "requestedMethodTime") == longNumber(planned, "methodTime")
                    && actualTime == longNumber(planned, "methodTime"),
                "Normal reference method clock differs");
            JsonObject patch = object(field(step, "externalPatch"), "externalPatch");
            require(patch.equals(field(planned, "externalPatch")), "Actual patch differs from declared input");
            actor.prepare(step, patch, actualTime);
            long consumed = actualTime;
            if (FAULT.equals(fault) && "L01_min_duration".equals(id) && index == 2) {
                require(
                    "tickOrStop".equals(operation) && actualTime == 145
                        && state.getEndTimestamp() == 145
                        && state.getStatus() == FollowCoreLookDriver.Status.RUNNING,
                    "Missing live deadline equality boundary");
                consumed++;
                JsonObject mutation = new JsonObject();
                mutation.addProperty("id", id);
                mutation.addProperty("rebuild", integer(row, "rebuild"));
                mutation.addProperty("index", index);
                mutation.addProperty("field", "methodTime");
                mutation.addProperty("captured", actualTime);
                mutation.addProperty("consumed", consumed);
                mutations.add(mutation);
            }
            JsonObject before = actor.snapshot(state);
            Boolean returned;
            switch (operation) {
                case "tryStart":
                    returned = driver.tryStart(actor, state, consumed);
                    break;
                case "tickOrStop":
                    driver.tickOrStop(actor, state, consumed);
                    returned = null;
                    break;
                case "doStop":
                    driver.doStop(actor, state, consumed);
                    returned = null;
                    break;
                default:
                    throw new IllegalArgumentException("Operation outside independent LOOK contract");
            }
            // 故障导致的少调用本身应进入比较结果，不通过未消费依赖标签代替行为差异检出
            if ("none".equals(fault)) actor.requireConsumed();
            JsonObject observed = new JsonObject();
            observed.addProperty("index", index);
            observed.addProperty("operation", operation);
            observed.addProperty("actualMethodTime", actualTime);
            observed.addProperty("consumedMethodTime", consumed);
            observed.add("before", before);
            observed.add("after", actor.snapshot(state));
            observed.addProperty("methodReturn", returned);
            observed.add("callTrace", actor.calls);
            observed.addProperty("unconsumedVisibilityObservations", actor.visibility.size() - actor.visibilityCursor);
            observed
                .addProperty("unconsumedWorldIntegerObservations", actor.worldIntegers.size() - actor.integerCursor);
            output.add(observed);
        }
        require(actor.worldIndex == actor.declaredIntegers.size(), "Declared world integer stream not consumed");
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("rebuild", integer(row, "rebuild"));
        result.add("steps", output);
        return result;
    }

    private static final class ReplayActor implements FollowCoreLookDriver.Actor {

        final JsonArray declaredIntegers;
        final JsonObject config;
        JsonObject previousObserved;
        JsonArray ownerPosition;
        final double ownerEyeHeight;
        final boolean registered;
        Tracker look;
        int nextTracker = 1, worldIndex, integerCursor, visibilityCursor;
        final List<JsonObject> worldIntegers = new ArrayList<JsonObject>(), visibility = new ArrayList<JsonObject>();
        JsonArray calls;

        ReplayActor(JsonObject row) {
            JsonObject spec = object(field(row, "input"), "input");
            config = copy(object(field(spec, "initial"), "initial"));
            keys(config, "lookPresent", "trackEye", "visibleMemory", "targetPosition");
            bool(config, "lookPresent");
            bool(config, "trackEye");
            visibleMode(config);
            vector(field(config, "targetPosition"), false);
            declaredIntegers = array(field(spec, "worldInts"), "worldInts");
            for (JsonElement value : declaredIntegers)
                require(strictLong(value, "world integer") >= 0, "Negative world integer");
            previousObserved = copy(object(field(row, "initialInputObservation"), "initialInputObservation"));
            inputShape(previousObserved);
            ownerPosition = vector(field(previousObserved, "ownerPosition"), false);
            ownerEyeHeight = number(previousObserved, "ownerEyeHeight");
            registered = bool(previousObserved, "lookRegistered");
            require(
                ownerEyeHeight > 0 && ownerPosition.equals(field(config, "targetPosition"))
                    && !field(previousObserved, "look").isJsonNull() == bool(config, "lookPresent"),
                "Initial actual target input differs");
            require(
                bool(previousObserved, "visibleCandidateContainsOwner") == "owner".equals(visibleMode(config)),
                "Initial visibility candidate input differs");
            if (bool(config, "lookPresent")) look = new Tracker("t" + nextTracker++, bool(config, "trackEye"));
            require(lookJson().equals(field(previousObserved, "look")), "Initial tracker identity/eye mapping differs");
        }

        void prepare(JsonObject step, JsonObject patch, long actualTime) {
            JsonObject observations = object(field(step, "inputObservations"), "inputObservations");
            JsonObject beforePatch = object(field(observations, "beforePatch"), "beforePatch");
            JsonObject beforeMethod = object(field(observations, "beforeMethod"), "beforeMethod");
            JsonObject afterMethod = object(field(observations, "afterMethod"), "afterMethod");
            inputShape(beforePatch);
            inputShape(beforeMethod);
            inputShape(afterMethod);
            require(beforePatch.equals(previousObserved), "Unpatched actual input continuity differs");
            JsonObject expected = copy(beforePatch);
            expected.addProperty("worldGameTime", actualTime);
            for (java.util.Map.Entry<String, JsonElement> entry : patch.entrySet()) {
                require(config.has(entry.getKey()), "Unknown external patch field");
                config.add(entry.getKey(), cloneJson(entry.getValue()));
            }
            bool(config, "lookPresent");
            bool(config, "trackEye");
            visibleMode(config);
            if (patch.has("targetPosition")) {
                ownerPosition = vector(field(config, "targetPosition"), false);
                expected.add("ownerPosition", cloneJson(ownerPosition));
                expected.add("ownerEyePosition", eyePosition(ownerPosition, ownerEyeHeight));
                if (!field(expected, "look").isJsonNull()) {
                    JsonObject tracker = object(field(expected, "look"), "previous look");
                    tracker.add(
                        "position",
                        bool(tracker, "trackEye") ? eyePosition(ownerPosition, ownerEyeHeight)
                            : cloneJson(ownerPosition));
                    tracker.add("block", floor(ownerPosition));
                }
            }
            if (patch.has("lookPresent") || patch.has("trackEye")) {
                look = bool(config, "lookPresent") ? new Tracker("t" + nextTracker++, bool(config, "trackEye")) : null;
                expected.add("look", lookJson());
            }
            if (patch.has("visibleMemory")) {
                expected.addProperty("visibleMemoryPresent", true);
                expected.addProperty("visibleCandidateContainsOwner", "owner".equals(visibleMode(config)));
            }
            require(expected.equals(beforeMethod), "Explicit patch or unpatched input binding differs");
            JsonObject expectedAfter = copy(beforeMethod);
            expectedAfter.add("look", cloneJson(field(afterMethod, "look")));
            require(expectedAfter.equals(afterMethod), "Read-only inputs changed during original method");
            if (!field(afterMethod, "look").isJsonNull()) require(
                field(afterMethod, "look").equals(field(beforeMethod, "look")),
                "LOOK-only method created/replaced tracker unexpectedly");
            previousObserved = copy(afterMethod); // 只供下一步原始输入连续性校验，不赋值给look或Driver.State
            JsonObject invocation = object(field(step, "methodInvocation"), "methodInvocation");
            keys(invocation, "operation", "timeArgument", "worldGameTime", "entityIsConfiguredMaid", "serverThread");
            require(
                text(invocation, "operation").equals(text(step, "operation"))
                    && longNumber(invocation, "timeArgument") == actualTime
                    && longNumber(invocation, "worldGameTime") == actualTime
                    && bool(invocation, "entityIsConfiguredMaid")
                    && bool(invocation, "serverThread"),
                "Actual method entry differs");
            worldIntegers.clear();
            visibility.clear();
            integerCursor = 0;
            visibilityCursor = 0;
            calls = new JsonArray();
            for (JsonElement value : array(field(step, "callTrace"), "callTrace")) {
                JsonObject call = object(value, "dependency call");
                switch (text(call, "op")) {
                    case "worldNextInt":
                        keys(call, "op", "bound", "value");
                        int bound = integer(call, "bound"), random = integer(call, "value");
                        require(bound > 0 && random >= 0 && random < bound, "Invalid observed bounded integer");
                        worldIntegers.add(call);
                        break;
                    case "isVisibleBy":
                        keys(call, "op", "target", "result");
                        require("owner".equals(text(call, "target")), "Unsupported visibility receiver");
                        bool(call, "result");
                        visibility.add(call);
                        break;
                    case "setLookAt":
                        break; // 策略输出，绝不读取target/before/after作为执行输入
                    default:
                        throw new IllegalArgumentException("Dependency outside independent LOOK contract");
                }
            }
        }

        public boolean isLookTargetRegistered() {
            return registered;
        }

        public boolean hasLookTarget() {
            return look != null;
        }

        public boolean isCurrentLookTargetVisible() {
            if (look == null) return false;
            require(visibilityCursor < visibility.size(), "Missing actual visibility dependency observation");
            JsonObject observed = visibility.get(visibilityCursor++);
            boolean result = bool(observed, "result");
            calls.add(cloneJson(observed));
            return result;
        }

        public void lookAtCurrentTargetIfPresent() {
            if (look == null) return;
            JsonObject call = new JsonObject();
            call.addProperty("op", "setLookAt");
            call.add("target", look.eye ? eyePosition(ownerPosition, ownerEyeHeight) : cloneJson(ownerPosition));
            calls.add(call);
        }

        public void eraseLookTarget() {
            look = null;
        }

        public int nextWorldInt(int bound) {
            require(integerCursor < worldIntegers.size(), "Missing actual world integer observation");
            JsonObject observed = worldIntegers.get(integerCursor++);
            require(
                integer(observed, "bound") == bound && worldIndex < declaredIntegers.size(),
                "World integer call bound/cursor differs");
            int result = integer(observed, "value");
            require(
                strictLong(declaredIntegers.get(worldIndex++), "declared integer") == result,
                "Recorded world integer differs from declared input");
            calls.add(cloneJson(observed));
            return result;
        }

        void requireConsumed() {
            require(
                integerCursor == worldIntegers.size() && visibilityCursor == visibility.size(),
                "Actual dependency observations not consumed exactly");
        }

        JsonElement lookJson() {
            if (look == null) return JsonNull.INSTANCE;
            JsonObject value = new JsonObject();
            value.addProperty("identity", look.identity);
            value.addProperty("class", TRACKER_CLASS);
            value.add("position", look.eye ? eyePosition(ownerPosition, ownerEyeHeight) : cloneJson(ownerPosition));
            value.add("block", floor(ownerPosition));
            value.addProperty("target", "owner");
            value.addProperty("trackEye", look.eye);
            return value;
        }

        JsonObject snapshot(FollowCoreLookDriver.State state) {
            JsonObject value = new JsonObject();
            value.add("look", lookJson());
            value.addProperty(
                "status",
                state.getStatus()
                    .name());
            value.addProperty("endTimestamp", state.getEndTimestamp());
            value.addProperty("worldIntegerIndex", worldIndex);
            return value;
        }
    }

    private static final class Tracker {

        final String identity;
        final boolean eye;

        Tracker(String identity, boolean eye) {
            this.identity = identity;
            this.eye = eye;
        }
    }

    private static void inputShape(JsonObject value) {
        keys(
            value,
            "worldGameTime",
            "maidPosition",
            "ownerPosition",
            "ownerEyeHeight",
            "ownerEyePosition",
            "ownerAlive",
            "ownerSpectator",
            "ownerResolved",
            "radius",
            "home",
            "canBrainMove",
            "breathing",
            "lookRegistered",
            "look",
            "visibleMemoryPresent",
            "visibleCandidateContainsOwner",
            "worldRegisteredMaid",
            "worldRegisteredOwner");
        longNumber(value, "worldGameTime");
        require(
            vector(field(value, "maidPosition"), false).equals(position(.5, 192, .5)) && number(value, "radius") == 8,
            "Outside fixed new LOOK fixture geometry");
        JsonArray at = vector(field(value, "ownerPosition"), false);
        double height = number(value, "ownerEyeHeight");
        require(
            height > 0 && vector(field(value, "ownerEyePosition"), false).equals(eyePosition(at, height)),
            "Invalid actual owner eye position");
        require(
            bool(value, "ownerAlive") && !bool(value, "ownerSpectator")
                && bool(value, "ownerResolved")
                && !bool(value, "home")
                && bool(value, "canBrainMove")
                && !bool(value, "breathing")
                && bool(value, "lookRegistered")
                && bool(value, "visibleMemoryPresent")
                && !bool(value, "worldRegisteredMaid")
                && !bool(value, "worldRegisteredOwner"),
            "Outside controlled LOOK actor domain");
        bool(value, "visibleCandidateContainsOwner");
        JsonElement look = field(value, "look");
        if (!look.isJsonNull()) {
            JsonObject tracker = object(look, "look");
            keys(tracker, "identity", "class", "position", "block", "target", "trackEye");
            require(
                text(tracker, "identity").matches("t[1-9][0-9]*") && TRACKER_CLASS.equals(text(tracker, "class"))
                    && "owner".equals(text(tracker, "target")),
                "Unknown actual tracker mapping");
            require(
                vector(field(tracker, "position"), false)
                    .equals(bool(tracker, "trackEye") ? eyePosition(at, height) : at)
                    && vector(field(tracker, "block"), true).equals(floor(at)),
                "Tracker position/eye/block mapping differs");
        }
    }

    private static void validateInitial(JsonObject state) {
        keys(state, "memory", "tasks", "navigation", "lookControl", "worldIntegerIndex");
        JsonObject memory = object(field(state, "memory"), "initial memory");
        keys(memory, "look", "walk", "path", "cantReachSince");
        require(
            field(memory, "walk").isJsonNull() && field(memory, "path").isJsonNull()
                && field(memory, "cantReachSince").isJsonNull(),
            "Unsupported initial non-LOOK memory");
        JsonObject tasks = object(field(state, "tasks"), "initial tasks");
        keys(tasks, "look", "move", "follow");
        for (String role : Arrays.asList("look", "follow", "move")) {
            JsonObject task = object(field(tasks, role), "initial task");
            if ("move".equals(role)) {
                keys(task, "status", "endTimestamp", "remainingCooldown", "path", "lastTargetPos", "speedModifier");
                require(
                    integer(task, "remainingCooldown") == 0 && field(task, "path").isJsonNull()
                        && field(task, "lastTargetPos").isJsonNull()
                        && number(task, "speedModifier") == 0,
                    "Unsupported initial move state");
            } else keys(task, "status", "endTimestamp");
            require(
                "STOPPED".equals(text(task, "status")) && longNumber(task, "endTimestamp") == 0,
                "Unsupported initial Behavior state");
        }
        JsonObject navigation = object(field(state, "navigation"), "initial navigation");
        keys(navigation, "path", "done", "stuck", "source");
        require(
            field(navigation, "path").isJsonNull() && bool(navigation, "done")
                && !bool(navigation, "stuck")
                && "controlled full path; no native navigation tick".equals(text(navigation, "source")),
            "Unsupported initial navigation");
        JsonObject control = object(field(state, "lookControl"), "initial lookControl");
        keys(control, "wanted", "cooldown", "yawSpeed", "pitchSpeed");
        require(
            vector(field(control, "wanted"), false).equals(position(0, 0, 0)) && integer(control, "cooldown") == 0
                && number(control, "yawSpeed") == 0
                && number(control, "pitchSpeed") == 0
                && integer(state, "worldIntegerIndex") == 0,
            "Unsupported initial helper state or RNG cursor");
    }

    private static String visibleMode(JsonObject config) {
        String mode = text(config, "visibleMemory");
        require("owner".equals(mode) || "empty".equals(mode), "Unknown visibility memory input");
        return mode;
    }

    private static JsonArray eyePosition(JsonArray at, double height) {
        return position(number(at.get(0)), number(at.get(1)) + height, number(at.get(2)));
    }

    private static JsonArray position(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(new JsonPrimitive(x));
        a.add(new JsonPrimitive(y));
        a.add(new JsonPrimitive(z));
        return a;
    }

    private static JsonArray floor(JsonArray at) {
        JsonArray result = new JsonArray();
        for (JsonElement value : at) {
            double floor = Math.floor(number(value));
            require(floor >= Integer.MIN_VALUE && floor <= Integer.MAX_VALUE, "Block coordinate outside int domain");
            result.add(new JsonPrimitive((int) floor));
        }
        return result;
    }

    private static JsonArray vector(JsonElement value, boolean integral) {
        JsonArray array = array(value, "vector");
        require(array.size() == 3, "Exactly three coordinates required");
        for (JsonElement item : array) {
            if (integral) {
                long n = strictLong(item, "block coordinate");
                require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "Block coordinate outside int domain");
            } else number(item);
        }
        return array;
    }

    private static JsonObject copy(JsonObject value) {
        return object(cloneJson(value), "copy");
    }

    private static JsonElement cloneJson(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (java.util.Map.Entry<String, JsonElement> e : value.getAsJsonObject()
                .entrySet()) out.add(e.getKey(), cloneJson(e.getValue()));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : value.getAsJsonArray()) out.add(cloneJson(e));
            return out;
        }
        return value;
    }

    private static void keys(JsonObject value, String... names) {
        Set<String> actual = new HashSet<String>();
        for (java.util.Map.Entry<String, JsonElement> e : value.entrySet()) actual.add(e.getKey());
        require(actual.equals(new HashSet<String>(Arrays.asList(names))), "Missing or unknown typed input fields");
    }

    private static JsonElement field(JsonObject value, String name) {
        require(value.has(name), "Missing required field: " + name);
        return value.get(name);
    }

    private static JsonObject object(JsonElement value, String where) {
        require(value != null && value.isJsonObject(), "Object required: " + where);
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String where) {
        require(value != null && value.isJsonArray(), "Array required: " + where);
        return value.getAsJsonArray();
    }

    private static String text(JsonObject value, String name) {
        return string(field(value, name), name);
    }

    private static String string(JsonElement value, String where) {
        require(
            value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isString(),
            "String required: " + where);
        return value.getAsString();
    }

    private static boolean bool(JsonObject value, String name) {
        JsonElement item = field(value, name);
        require(
            item.isJsonPrimitive() && item.getAsJsonPrimitive()
                .isBoolean(),
            "Boolean required: " + name);
        return item.getAsBoolean();
    }

    private static double number(JsonObject value, String name) {
        return number(field(value, name));
    }

    private static double number(JsonElement value) {
        require(
            value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isNumber(),
            "Numeric scalar required");
        double result = value.getAsDouble();
        require(Double.isFinite(result), "Finite scalar required");
        return result;
    }

    private static long longNumber(JsonObject value, String name) {
        return strictLong(field(value, name), name);
    }

    private static long strictLong(JsonElement value, String where) {
        number(value);
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Exact long required: " + where);
        }
    }

    private static int integer(JsonObject value, String name) {
        long n = longNumber(value, name);
        require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "Int required: " + name);
        return (int) n;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
