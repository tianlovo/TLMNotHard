package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 手工协议夹具只验证重放器输入隔离和故障路径，不是原版oracle或实跑成绩 */
public class LookCoreReplayMainTest {

    @Test
    public void runsActualDriverWithoutUsingOracleAnswersOrNativeHelperTarget() {
        JsonObject row = fixture();
        for (JsonElement value : row.getAsJsonArray("steps")) {
            JsonObject step = value.getAsJsonObject();
            step.addProperty("before", "poison state answer");
            step.addProperty("after", "poison state answer");
            step.addProperty("methodReturn", "poison return answer");
            for (JsonElement call : step.getAsJsonArray("callTrace")) {
                if ("setLookAt".equals(
                    call.getAsJsonObject()
                        .get("op")
                        .getAsString()))
                    call.getAsJsonObject()
                        .add("target", json("[999,999,999]"));
            }
        }
        JsonObject output = LookCoreReplayMain.replayRow(row, "none", new JsonArray());
        assertEquals(
            145,
            after(output, 0).get("endTimestamp")
                .getAsLong());
        assertEquals(
            "RUNNING",
            after(output, 2).get("status")
                .getAsString());
        assertEquals(
            json("[0.5,193.62,8.5]"),
            step(output, 2).getAsJsonArray("callTrace")
                .get(1)
                .getAsJsonObject()
                .get("target"));
        assertEquals(
            "STOPPED",
            after(output, 3).get("status")
                .getAsString());
        assertTrue(
            after(output, 3).get("look")
                .isJsonNull());
    }

    @Test
    public void deadlineFaultExecutesDifferentPolicyAndCleanReplayRecoversWithoutMutatingInput() {
        JsonObject row = fixture();
        String before = row.toString();
        JsonObject plain = LookCoreReplayMain.replayRow(row, "none", new JsonArray());
        JsonArray mutations = new JsonArray();
        JsonObject faulty = LookCoreReplayMain.replayRow(row, "deadline-equality-plus-one", mutations);
        assertEquals(1, mutations.size());
        assertEquals(
            "RUNNING",
            after(plain, 2).get("status")
                .getAsString());
        assertEquals(
            "STOPPED",
            after(faulty, 2).get("status")
                .getAsString());
        assertTrue(
            after(faulty, 2).get("look")
                .isJsonNull());
        assertEquals(
            0,
            step(faulty, 2).getAsJsonArray("callTrace")
                .size());
        assertEquals(
            1,
            step(faulty, 2).get("unconsumedVisibilityObservations")
                .getAsInt());
        assertEquals(
            145,
            step(faulty, 2).get("actualMethodTime")
                .getAsLong());
        assertEquals(
            146,
            step(faulty, 2).get("consumedMethodTime")
                .getAsLong());
        assertEquals(before, row.toString());
        assertEquals(plain, LookCoreReplayMain.replayRow(row, "none", new JsonArray()));
    }

    @Test
    public void explicitRemovalDoesNotReinsertConfiguredInitialLookOnFollowingStep() {
        JsonObject row = fixture();
        JsonObject plan = row.getAsJsonObject("input")
            .getAsJsonArray("steps")
            .get(2)
            .getAsJsonObject();
        plan.getAsJsonObject("externalPatch")
            .addProperty("lookPresent", false);
        step(row, 2).getAsJsonObject("externalPatch")
            .addProperty("lookPresent", false);
        step(row, 2).getAsJsonObject("inputObservations")
            .getAsJsonObject("beforeMethod")
            .add("look", JsonNull.INSTANCE);
        step(row, 2).getAsJsonObject("inputObservations")
            .getAsJsonObject("afterMethod")
            .add("look", JsonNull.INSTANCE);
        step(row, 2).add("callTrace", new JsonArray());
        for (String name : new String[] { "beforePatch", "beforeMethod", "afterMethod" })
            step(row, 3).getAsJsonObject("inputObservations")
                .getAsJsonObject(name)
                .add("look", JsonNull.INSTANCE);
        JsonObject output = LookCoreReplayMain.replayRow(row, "none", new JsonArray());
        assertTrue(
            after(output, 2).get("look")
                .isJsonNull());
        assertTrue(
            step(output, 3).getAsJsonObject("before")
                .get("look")
                .isJsonNull());
        assertEquals(
            0,
            step(output, 3).getAsJsonArray("callTrace")
                .size());
    }

    @Test
    public void incompleteTypedInputsDriftAndNonDefaultInitialStateAreRejected() {
        JsonObject missing = fixture();
        missing.getAsJsonObject("initialInputObservation")
            .remove("ownerEyeHeight");
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(missing, "none", new JsonArray()));
        JsonObject drift = fixture();
        step(drift, 1).getAsJsonObject("inputObservations")
            .getAsJsonObject("beforeMethod")
            .addProperty("radius", 9);
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(drift, "none", new JsonArray()));
        JsonObject state = fixture();
        state.getAsJsonObject("initialState")
            .getAsJsonObject("tasks")
            .getAsJsonObject("look")
            .addProperty("endTimestamp", 42);
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(state, "none", new JsonArray()));
        JsonObject clock = fixture();
        step(clock, 2).getAsJsonObject("methodInvocation")
            .addProperty("timeArgument", 146);
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(clock, "none", new JsonArray()));
        JsonObject bounds = fixture();
        step(bounds, 0).getAsJsonArray("callTrace")
            .get(0)
            .getAsJsonObject()
            .addProperty("bound", 45);
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(bounds, "none", new JsonArray()));
    }

    @Test
    public void parserAndEntryRejectDuplicateKeysUnknownSchemaAndMissingCaseSet() throws Exception {
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.parseStrict(new StringReader("{\"schema\":1,\"schema\":2}")));
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replay(json("{\"schema\":\"unknown\"}").getAsJsonObject(), "none"));
        assertThrows(
            IllegalArgumentException.class,
            () -> LookCoreReplayMain.replayRow(fixture(), "unknown", new JsonArray()));
        JsonObject header = json(
            "{\"schema\":\"look-reference-capture-v1\",\"inputObservationSchema\":\"look-component-input-observations-v1\",\"fault\":\"none\",\"complete\":true,\"naturalBrainVerified\":false,\"nativeHelperTicked\":false,\"nativeNavigationVerified\":false,\"contractSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"contracts\":[\"LOOK_SINK_CORE_V1\",\"FOLLOW_MOVE_LOOK_COMPONENT_V2\"],\"cases\":[]}")
                .getAsJsonObject();
        header.getAsJsonArray("cases")
            .add(fixture());
        assertThrows(IllegalArgumentException.class, () -> LookCoreReplayMain.replay(header, "none"));
        JsonObject row = fixture();
        row.addProperty("id", "not-the-boundary");
        JsonArray mutations = new JsonArray();
        LookCoreReplayMain.replayRow(row, "deadline-equality-plus-one", mutations);
        assertEquals(0, mutations.size());
        assertFalse(row.has("output"));
    }

    private static JsonObject fixture() {
        JsonObject row = new JsonObject();
        row.addProperty("id", "L01_min_duration");
        row.addProperty("rebuild", 0);
        row.addProperty("contract", "LOOK_SINK_CORE_V1");
        for (String flag : new String[] { "complete", "cleanupComplete", "worldRandomRestored", "worldClockRestored" })
            row.addProperty(flag, true);
        JsonObject spec = json(
            "{\"id\":\"L01_min_duration\",\"kind\":\"look\",\"initial\":{\"lookPresent\":true,\"trackEye\":true,\"visibleMemory\":\"owner\",\"targetPosition\":[0.5,192,8.5]},\"worldInts\":[0],\"steps\":[]}")
                .getAsJsonObject();
        row.add("input", spec);
        row.add("initialInputObservation", observation(100, true));
        JsonObject initial = json(
            "{\"memory\":{\"look\":null,\"walk\":null,\"path\":null,\"cantReachSince\":null},\"tasks\":{\"look\":{\"status\":\"STOPPED\",\"endTimestamp\":0},\"follow\":{\"status\":\"STOPPED\",\"endTimestamp\":0},\"move\":{\"status\":\"STOPPED\",\"endTimestamp\":0,\"remainingCooldown\":0,\"path\":null,\"lastTargetPos\":null,\"speedModifier\":0}},\"navigation\":{\"path\":null,\"done\":true,\"stuck\":false,\"source\":\"controlled full path; no native navigation tick\"},\"lookControl\":{\"wanted\":[0,0,0],\"cooldown\":0,\"yawSpeed\":0,\"pitchSpeed\":0},\"worldIntegerIndex\":0}")
                .getAsJsonObject();
        initial.getAsJsonObject("memory")
            .add("look", tracker());
        row.add("initialState", initial);
        JsonArray steps = new JsonArray();
        row.add("steps", steps);
        int[] times = { 100, 100, 145, 146 };
        for (int i = 0; i < times.length; i++) {
            String operation = i == 0 ? "tryStart" : "tickOrStop";
            JsonObject planned = new JsonObject();
            planned.addProperty("operation", operation);
            planned.addProperty("methodTime", times[i]);
            planned.add("externalPatch", new JsonObject());
            spec.getAsJsonArray("steps")
                .add(planned);
            JsonObject step = new JsonObject();
            step.addProperty("index", i);
            step.addProperty("operation", operation);
            step.addProperty("requestedMethodTime", times[i]);
            step.addProperty("actualMethodTime", times[i]);
            step.addProperty("complete", true);
            step.add("externalPatch", new JsonObject());
            JsonObject obs = new JsonObject();
            obs.add("beforePatch", observation(i == 0 ? 100 : times[i - 1], true));
            obs.add("beforeMethod", observation(times[i], true));
            obs.add("afterMethod", observation(times[i], i != 3));
            step.add("inputObservations", obs);
            JsonObject invocation = new JsonObject();
            invocation.addProperty("operation", operation);
            invocation.addProperty("timeArgument", times[i]);
            invocation.addProperty("worldGameTime", times[i]);
            invocation.addProperty("entityIsConfiguredMaid", true);
            invocation.addProperty("serverThread", true);
            step.add("methodInvocation", invocation);
            JsonArray trace = new JsonArray();
            if (i == 0) trace.add(json("{\"op\":\"worldNextInt\",\"bound\":46,\"value\":0}"));
            else if (i < 3) {
                trace.add(json("{\"op\":\"isVisibleBy\",\"target\":\"owner\",\"result\":true}"));
                trace.add(json("{\"op\":\"setLookAt\",\"target\":[0.5,193.62,8.5]}"));
            }
            step.add("callTrace", trace);
            steps.add(step);
        }
        return row;
    }

    private static JsonObject observation(long time, boolean look) {
        JsonObject value = json(
            "{\"worldGameTime\":100,\"maidPosition\":[0.5,192,0.5],\"ownerPosition\":[0.5,192,8.5],\"ownerEyeHeight\":1.62,\"ownerEyePosition\":[0.5,193.62,8.5],\"ownerAlive\":true,\"ownerSpectator\":false,\"ownerResolved\":true,\"radius\":8,\"home\":false,\"canBrainMove\":true,\"breathing\":false,\"lookRegistered\":true,\"look\":null,\"visibleMemoryPresent\":true,\"visibleCandidateContainsOwner\":true,\"worldRegisteredMaid\":false,\"worldRegisteredOwner\":false}")
                .getAsJsonObject();
        value.addProperty("worldGameTime", time);
        if (look) value.add("look", tracker());
        return value;
    }

    private static JsonObject tracker() {
        return json(
            "{\"identity\":\"t1\",\"class\":\"net.minecraft.world.entity.ai.behavior.EntityTracker\",\"position\":[0.5,193.62,8.5],\"block\":[0,192,8],\"target\":\"owner\",\"trackEye\":true}")
                .getAsJsonObject();
    }

    private static JsonElement json(String source) {
        return new JsonParser().parse(source);
    }

    private static JsonObject step(JsonObject row, int index) {
        return row.getAsJsonArray("steps")
            .get(index)
            .getAsJsonObject();
    }

    private static JsonObject after(JsonObject row, int index) {
        return step(row, index).getAsJsonObject("after");
    }
}
