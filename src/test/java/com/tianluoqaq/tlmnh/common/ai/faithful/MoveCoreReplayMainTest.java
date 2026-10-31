package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 手工协议输入只验证重放器，不作为原版oracle或新进程实验证据 */
public class MoveCoreReplayMainTest {

    @Test
    public void actualStatePersistsAcrossStepsWithoutReadingOracleBeforeOrAfter() {
        var reference = fixture();
        for (var item : row(reference).getAsJsonArray("steps")) {
            item.getAsJsonObject()
                .addProperty("before", "poison oracle snapshot");
            item.getAsJsonObject()
                .addProperty("after", "poison oracle snapshot");
            item.getAsJsonObject()
                .addProperty("methodReturn", "poison answer");
        }
        var replay = MoveCoreReplayMain.replay(reference, "none");
        assertEquals(
            "RUNNING",
            sink(replay, 0).get("status")
                .getAsString());
        assertEquals(
            250,
            sink(replay, 0).get("endTimestamp")
                .getAsLong());
        assertEquals(
            "RUNNING",
            sink(replay, 1).get("status")
                .getAsString());
        assertEquals(
            "A",
            sink(replay, 1).get("path")
                .getAsString());
        assertEquals(
            "STOPPED",
            sink(replay, 2).get("status")
                .getAsString());
        assertTrue(
            sink(replay, 2).get("path")
                .isJsonNull());
        assertTrue(
            step(replay, 2).getAsJsonObject("after")
                .getAsJsonObject("memory")
                .get("walk")
                .isJsonNull());
        assertEquals(
            0,
            step(replay, 1).getAsJsonArray("callTrace")
                .size());
    }

    @Test
    public void deadlineFaultChangesRealExecutionAndUnmodifiedReplayRecovers() {
        var reference = fixture();
        String original = reference.toString();
        var plain = MoveCoreReplayMain.replay(reference, "none");
        var fault = MoveCoreReplayMain.replay(reference, "deadline-equality-plus-one");
        assertEquals(
            "RUNNING",
            sink(plain, 1).get("status")
                .getAsString());
        assertEquals(
            "STOPPED",
            sink(fault, 1).get("status")
                .getAsString());
        assertEquals(
            "stopNavigation",
            step(fault, 1).getAsJsonArray("callTrace")
                .get(0)
                .getAsJsonObject()
                .get("op")
                .getAsString());
        assertEquals(
            250,
            step(fault, 1).get("methodTime")
                .getAsLong());
        assertEquals(
            251,
            step(fault, 1).get("consumedMethodTime")
                .getAsLong());
        assertEquals(
            1,
            fault.getAsJsonArray("inputMutations")
                .size());
        assertEquals(original, reference.toString());
        assertEquals(plain, MoveCoreReplayMain.replay(reference, "none"));
        row(reference).addProperty("id", "not-the-boundary-fixture");
        assertThrows(
            IllegalArgumentException.class,
            () -> MoveCoreReplayMain.replay(reference, "deadline-equality-plus-one"));
    }

    @Test
    public void explicitPatchChangesLiveEntityTrackerWithoutRestoringOldMemory() {
        var reference = fixture();
        step(reference, 1).getAsJsonObject("input")
            .getAsJsonObject("externalPatch")
            .add("targetBlock", new JsonParser().parse("[2,192,0]"));
        targetObserved(reference, 1, 2);
        targetObserved(reference, 2, 2);
        var replay = MoveCoreReplayMain.replay(reference, "none");
        assertEquals(
            "STOPPED",
            sink(replay, 1).get("status")
                .getAsString());
        assertTrue(
            step(replay, 1).getAsJsonObject("after")
                .getAsJsonObject("memory")
                .get("walk")
                .isJsonNull());
        assertEquals(
            "RUNNING",
            step(replay, 1).getAsJsonObject("before")
                .getAsJsonObject("sink")
                .get("status")
                .getAsString());
    }

    @Test
    public void partialWalkConfigurationPatchReusesOnlyConfigurationWhenExplicitlyRequested() {
        var reference = fixture();
        step(reference, 1).getAsJsonObject("input")
            .getAsJsonObject("externalPatch")
            .add("targetBlock", new JsonParser().parse("[2,192,0]"));
        targetObserved(reference, 1, 2);
        var restart = new JsonParser().parse(step(reference, 0).toString())
            .getAsJsonObject();
        restart.addProperty("index", 2);
        restart.addProperty("methodTime", 251);
        restart.addProperty("worldGameTime", 751);
        restart.getAsJsonObject("methodInvocation")
            .addProperty("methodTime", 251);
        restart.getAsJsonObject("methodInvocation")
            .addProperty("worldGameTime", 751);
        restart.getAsJsonObject("inputBeforeInvocation")
            .addProperty("worldGameTime", 751);
        restart.getAsJsonObject("inputAfterInvocation")
            .addProperty("worldGameTime", 751);
        restart.getAsJsonObject("input")
            .add(
                "externalPatch",
                new JsonParser()
                    .parse("{\"targetBlock\":[8,192,0],\"walk\":{\"present\":true},\"createReplies\":[\"A\"]}"));
        var originalSteps = row(reference).getAsJsonArray("steps");
        var steps = new com.google.gson.JsonArray();
        steps.add(originalSteps.get(0));
        steps.add(originalSteps.get(1));
        steps.add(restart);
        row(reference).add("steps", steps);
        String unmodified = reference.toString();
        var replay = MoveCoreReplayMain.replay(reference, "none");
        assertEquals(
            "STOPPED",
            sink(replay, 1).get("status")
                .getAsString());
        assertEquals(
            "RUNNING",
            sink(replay, 2).get("status")
                .getAsString());
        assertEquals(
            401,
            sink(replay, 2).get("endTimestamp")
                .getAsLong());
        assertEquals(unmodified, reference.toString());
    }

    @Test
    public void actualCreateCallbackPositionIsConsumedBeforeSecondArrivalCheck() {
        var reference = fixture();
        step(reference, 0).add(
            "callbackObservations",
            new JsonParser()
                .parse("[{\"createIndex\":0,\"from\":[0,192,0],\"to\":[8,192,0],\"actualAfterBlock\":[8,192,0]}]"));
        step(reference, 0).add("inputAfterInvocation", inputObservation(600, 8, 8, false));
        for (int index = 1; index < 3; index++) {
            long time = step(reference, index).get("worldGameTime")
                .getAsLong();
            step(reference, index).add("inputBeforeInvocation", inputObservation(time, 8, 8, false));
            step(reference, index).add("inputAfterInvocation", inputObservation(time, 8, 8, false));
        }
        var replay = MoveCoreReplayMain.replay(reference, "none");
        assertEquals(
            false,
            step(replay, 0).get("methodReturn")
                .getAsBoolean());
        assertEquals(
            1,
            step(replay, 0).getAsJsonArray("callTrace")
                .size());
        assertEquals(
            "STOPPED",
            sink(replay, 0).get("status")
                .getAsString());
        assertEquals(
            "A",
            sink(replay, 0).get("path")
                .getAsString());
        assertTrue(
            sink(replay, 0).get("lastTargetPos")
                .isJsonNull());
    }

    @Test
    public void partialPathUsesActualWorldClockWithoutRewritingItAtStop() {
        var reference = fixture();
        row(reference).getAsJsonObject("paths")
            .getAsJsonObject("A")
            .addProperty("canReach", false);
        var replay = MoveCoreReplayMain.replay(reference, "none");
        assertEquals(
            600,
            step(replay, 0).getAsJsonObject("after")
                .getAsJsonObject("memory")
                .get("cantReachSince")
                .getAsLong());
        assertEquals(
            600,
            step(replay, 2).getAsJsonObject("after")
                .getAsJsonObject("memory")
                .get("cantReachSince")
                .getAsLong());
    }

    @Test
    public void strictReaderRejectsDuplicateKeysAndUnreviewedStructure() throws Exception {
        assertThrows(
            IllegalArgumentException.class,
            () -> MoveCoreReplayMain.parseStrict(new StringReader("{\"schema\":1,\"schema\":2}")));
        assertThrows(
            IllegalArgumentException.class,
            () -> MoveCoreReplayMain.parseStrict(new StringReader("{\"child\":{\"x\":1,\"x\":2}}")));
        var unknown = fixture();
        unknown.addProperty("schema", "move-sink-reference-future");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(unknown, "none"));
        var duplicate = fixture();
        row(duplicate).getAsJsonArray("steps")
            .get(1)
            .getAsJsonObject()
            .addProperty("index", 0);
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(duplicate, "none"));
        var duplicateCase = fixture();
        duplicateCase.getAsJsonArray("cases")
            .add(new JsonParser().parse(row(duplicateCase).toString()));
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(duplicateCase, "none"));
    }

    @Test
    public void requiredInputsAndObservedDependencyFieldsCannotDefaultToZero() {
        for (String field : new String[] { "methodTime", "worldGameTime", "input", "callTrace", "moveObservations",
            "callbackObservations", "fallbackObservation", "inputBeforeInvocation", "inputAfterInvocation",
            "methodInvocation" }) {
            var reference = fixture();
            step(reference, 0).remove(field);
            assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(reference, "none"));
        }
        var noPosition = fixture();
        row(noPosition).remove("initialMaidBlock");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(noPosition, "none"));
        var noReach = fixture();
        row(noReach).getAsJsonObject("paths")
            .getAsJsonObject("A")
            .remove("canReach");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(noReach, "none"));
        var wrongBoolean = fixture();
        row(wrongBoolean).getAsJsonObject("initialState")
            .getAsJsonObject("navigation")
            .addProperty("done", "true");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(wrongBoolean, "none"));
        var oldObservation = fixture();
        oldObservation.remove("inputObservationSchema");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(oldObservation, "none"));
        var noInitialObservation = fixture();
        row(noInitialObservation).remove("initialInputObservation");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(noInitialObservation, "none"));
    }

    @Test
    public void unknownPathsAndMissingRandomObservationFailClosed() {
        var unknownPath = fixture();
        step(unknownPath, 0).getAsJsonArray("callTrace")
            .get(0)
            .getAsJsonObject()
            .addProperty("resultPath", "missing");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(unknownPath, "none"));
        var missingRandom = fixture();
        var originalCalls = step(missingRandom, 0).getAsJsonArray("callTrace");
        var withoutRandom = new com.google.gson.JsonArray();
        withoutRandom.add(originalCalls.get(0));
        withoutRandom.add(originalCalls.get(2));
        step(missingRandom, 0).add("callTrace", withoutRandom);
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(missingRandom, "none"));
        var wrongBound = fixture();
        step(wrongBound, 0).getAsJsonArray("callTrace")
            .get(1)
            .getAsJsonObject()
            .addProperty("bound", 100);
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(wrongBound, "none"));
    }

    @Test
    public void observedMoveSideEffectsMustStartFromActualReplayNavigation() {
        var reference = fixture();
        step(reference, 0).getAsJsonArray("moveObservations")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("before")
            .addProperty("path", "A");
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(reference, "none"));
    }

    @Test
    public void actualInvocationAndCallbackReadbackCannotBeReplacedByPlanLabels() {
        var reference = fixture();
        step(reference, 0).getAsJsonObject("methodInvocation")
            .addProperty("methodTime", 0);
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(reference, "none"));
        var wrongCallback = fixture();
        step(wrongCallback, 0).add(
            "callbackObservations",
            new JsonParser()
                .parse("[{\"createIndex\":0,\"from\":[0,192,0],\"to\":[8,192,0],\"actualAfterBlock\":[0,192,0]}]"));
        assertThrows(IllegalArgumentException.class, () -> MoveCoreReplayMain.replay(wrongCallback, "none"));
    }

    private static JsonObject fixture() {
        String emptyNavigation = "{\"path\":null,\"done\":true,\"stuck\":false}";
        String runningNavigation = "{\"path\":\"A\",\"done\":false,\"stuck\":false}";
        String noFallback = "{\"invoked\":false,\"queryBlock\":null,\"directVec3Observed\":false}";
        JsonObject result = new JsonParser().parse(
            "{\"schema\":\"move-sink-reference-v1\","
                + "\"contractSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\","
                + "\"cases\":[{\"id\":\"S01_duration_min\",\"rebuild\":0,"
                + "\"input\":{\"initial\":{\"walk\":{\"present\":true,\"tracker\":\"entity\",\"speed\":0.5,\"closeEnough\":2}}},"
                + "\"initialMaidBlock\":[0,192,0],\"initialTargetBlock\":[8,192,0],\"initialTargetSpectator\":false,"
                + "\"paths\":{\"A\":{\"canReach\":true,\"target\":[8,192,0],\"nodes\":[[0,192,0],[8,192,0]]}},"
                + "\"initialState\":{\"memory\":{\"registered\":{\"walk\":true,\"path\":true,\"cantReachSince\":true},"
                + "\"walk\":{\"block\":[8,192,0],\"speed\":0.5,\"closeEnough\":2,\"entityTrackerSpectator\":false},"
                + "\"path\":null,\"cantReachSince\":null},\"sink\":{\"status\":\"STOPPED\",\"remainingCooldown\":0,"
                + "\"path\":null,\"lastTargetPos\":null,\"speedModifier\":0,\"endTimestamp\":0},\"navigation\":"
                + emptyNavigation
                + "},"
                + "\"steps\":[{\"index\":0,\"operation\":\"tryStart\",\"methodTime\":100,\"worldGameTime\":600,"
                + "\"input\":{\"externalPatch\":{\"createReplies\":[\"A\"]}},\"callTrace\":["
                + "{\"op\":\"createPath\",\"entry\":\"block\",\"target\":[8,192,0],\"accuracy\":0,\"resultPath\":\"A\"},"
                + "{\"op\":\"worldNextInt\",\"bound\":101,\"value\":0},"
                + "{\"op\":\"moveTo\",\"path\":\"A\",\"speed\":0.5,\"result\":true}],"
                + "\"moveObservations\":[{\"path\":\"A\",\"speed\":0.5,\"result\":true,\"before\":"
                + emptyNavigation
                + ",\"after\":"
                + runningNavigation
                + "}],"
                + "\"callbackObservations\":[],\"fallbackObservation\":"
                + noFallback
                + "},"
                + "{\"index\":1,\"operation\":\"tickOrStop\",\"methodTime\":250,\"worldGameTime\":750,\"input\":{\"externalPatch\":{}},"
                + "\"callTrace\":[],\"moveObservations\":[],\"callbackObservations\":[],\"fallbackObservation\":"
                + noFallback
                + "},"
                + "{\"index\":2,\"operation\":\"tickOrStop\",\"methodTime\":251,\"worldGameTime\":751,\"input\":{\"externalPatch\":{}},"
                + "\"callTrace\":[{\"op\":\"stopNavigation\"}],\"moveObservations\":[],\"callbackObservations\":[],\"fallbackObservation\":"
                + noFallback
                + "}]}]}")
            .getAsJsonObject();
        result.addProperty("inputObservationSchema", "move-sink-input-observations-v1");
        row(result).add("initialInputObservation", inputObservation(0, 0, 8, true));
        for (int index = 0; index < 3; index++) {
            JsonObject step = step(result, index);
            long time = step.get("worldGameTime")
                .getAsLong();
            step.add("inputBeforeInvocation", inputObservation(time, 0, 8, true));
            step.add("inputAfterInvocation", inputObservation(time, 0, 8, index != 2));
            var invocation = new JsonObject();
            invocation.addProperty(
                "operation",
                step.get("operation")
                    .getAsString());
            invocation.addProperty(
                "methodTime",
                step.get("methodTime")
                    .getAsLong());
            invocation.addProperty("worldGameTime", time);
            invocation.addProperty("entityIsFixtureMaid", true);
            invocation.addProperty("worldIsFixtureLevel", true);
            step.add("methodInvocation", invocation);
        }
        return result;
    }

    private static JsonObject inputObservation(long world, int maidX, int targetX, boolean walk) {
        var result = new JsonObject();
        result.add("maidBlock", new JsonParser().parse("[" + maidX + ",192,0]"));
        result.add("maidPosition", new JsonParser().parse("[" + (maidX + 0.5) + ",192,0.5]"));
        result.add("targetEntityBlock", new JsonParser().parse("[" + targetX + ",192,0]"));
        result.add("targetEntityPosition", new JsonParser().parse("[" + (targetX + 0.5) + ",192,0.5]"));
        result.addProperty("targetSpectator", false);
        result.addProperty("worldGameTime", world);
        result.add(
            "walkTracker",
            walk ? new JsonParser().parse(
                "{\"type\":\"entity\",\"block\":[" + targetX
                    + ",192,0],\"currentPosition\":["
                    + (targetX + 0.5)
                    + ",193.5,0.5],\"entityIsConfiguredTarget\":true,\"entityUuid\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"entityRuntimeClass\":\"synthetic.Target\",\"entitySpectator\":false}")
                : com.google.gson.JsonNull.INSTANCE);
        return result;
    }

    private static void targetObserved(JsonObject reference, int index, int targetX) {
        long time = step(reference, index).get("worldGameTime")
            .getAsLong();
        step(reference, index).add("inputBeforeInvocation", inputObservation(time, 0, targetX, index == 1));
        step(reference, index).add("inputAfterInvocation", inputObservation(time, 0, targetX, false));
    }

    private static JsonObject row(JsonObject document) {
        return document.getAsJsonArray("cases")
            .get(0)
            .getAsJsonObject();
    }

    private static JsonObject step(JsonObject document, int index) {
        return row(document).getAsJsonArray("steps")
            .get(index)
            .getAsJsonObject();
    }

    private static JsonObject sink(JsonObject document, int index) {
        return step(document, index).getAsJsonObject("after")
            .getAsJsonObject("sink");
    }
}
