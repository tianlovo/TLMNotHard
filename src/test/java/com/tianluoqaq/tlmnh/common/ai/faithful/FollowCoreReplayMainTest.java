package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** 手工构造的协议单测输入，不是原版采集或原版行为对照证据 */
public class FollowCoreReplayMainTest {

    @Test
    public void actualCoreRunsWithoutReadingCapturedNormalisedAnswers() {
        var input = fixture();
        row(input).addProperty("normalised", "deliberately not an answer object");
        JsonObject normalised = normalised(FollowCoreReplayMain.replay(input, "none"));
        assertTrue(
            normalised.get("started")
                .getAsBoolean());
        assertEquals(
            2,
            normalised.get("ownerQueries")
                .getAsInt());
        assertEquals(
            "owner",
            normalised.getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertEquals(
            "owner",
            normalised.get("look")
                .getAsString());
        assertFalse(
            normalised.get("walkTargetRetained")
                .getAsBoolean());
    }

    @Test
    public void missingOrWrongTypedActualFieldsAreRejected() {
        for (String missing : new String[] { "radius", "distanceSquared", "home", "canBrainMove", "breathing",
            "ownerResolved", "ownerSpectator", "ownerDeadOrDying", "sameLevel", "ownerUnderwater" }) {
            var input = fixture();
            actual(input).remove(missing);
            assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(input, "none"));
        }
        var wrongBoolean = fixture();
        actual(wrongBoolean).addProperty("home", "false");
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(wrongBoolean, "none"));
        var wrongNumber = fixture();
        actual(wrongNumber).addProperty("distanceSquared", "36");
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(wrongNumber, "none"));
    }

    @Test
    public void offlineOwnerNeedsExplicitNullsAndIsResolvedOnlyForTheGate() {
        var input = fixture();
        actual(input).addProperty("ownerResolved", false);
        for (String key : new String[] { "distanceSquared", "ownerSpectator", "ownerDeadOrDying", "sameLevel",
            "ownerUnderwater" }) {
            actual(input).add(key, com.google.gson.JsonNull.INSTANCE);
        }
        JsonObject normalised = normalised(FollowCoreReplayMain.replay(input, "none"));
        assertFalse(
            normalised.get("started")
                .getAsBoolean());
        assertEquals(
            1,
            normalised.get("ownerQueries")
                .getAsInt());
        assertEquals(
            "none",
            normalised.getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertFalse(
            normalised.get("walkTargetRetained")
                .getAsBoolean());
        actual(input).remove("sameLevel");
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(input, "none"));
    }

    @Test
    public void distanceFaultChangesTheInputConsumedByTheRealCoreWithoutChangingCapture() {
        var input = fixture();
        String original = input.toString();
        var plain = FollowCoreReplayMain.replay(input, "none");
        var fault = FollowCoreReplayMain.replay(input, "input-distance-below-six");
        assertEquals(
            "owner",
            normalised(plain).getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertEquals(
            "none",
            normalised(fault).getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertEquals(
            1,
            fault.getAsJsonArray("inputMutations")
                .size());
        assertEquals(original, input.toString());
        row(input).addProperty("id", "not-the-fault-target");
        assertThrows(
            IllegalArgumentException.class,
            () -> FollowCoreReplayMain.replay(input, "input-distance-below-six"));
    }

    @Test
    public void teleportStubUsesObservedMemoryAndIdentityEffectsRatherThanModeName() {
        var input = fixture();
        actual(input).addProperty("distanceSquared", 100);
        JsonObject before = row(input).getAsJsonObject("before");
        before.add("walk", walk("owner", 0.75, 3));
        before.addProperty("look", "other");
        before.addProperty("attack", "other");
        before.addProperty("path", "present");
        before.addProperty("targetPos", "other");
        var after = copy(before);
        after.add("walk", walk("none", null, null));
        after.addProperty("look", "none");
        after.addProperty("attack", "none");
        after.addProperty("path", "none");
        teleport(input, true, true, false, before, after);
        JsonObject normalised = normalised(FollowCoreReplayMain.replay(input, "none"));
        assertEquals(
            "none",
            normalised.getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertEquals(
            "none",
            normalised.get("attack")
                .getAsString());
        assertEquals(
            "other",
            normalised.get("targetPos")
                .getAsString());
        assertEquals(
            1,
            normalised.get("teleportCalls")
                .getAsInt());
        assertEquals(
            1,
            normalised.get("resetCalls")
                .getAsInt());
        assertFalse(
            normalised.get("walkTargetRetained")
                .getAsBoolean());

        teleport(input, false, false, true, before, copy(before));
        normalised = normalised(FollowCoreReplayMain.replay(input, "none"));
        assertEquals(
            "owner",
            normalised.getAsJsonObject("walk")
                .get("target")
                .getAsString());
        assertEquals(
            "other",
            normalised.get("attack")
                .getAsString());
        assertEquals(
            "present",
            normalised.get("path")
                .getAsString());
        assertTrue(
            normalised.get("walkTargetRetained")
                .getAsBoolean());
    }

    @Test
    public void missingOrMismatchedNativeEffectEvidenceIsRejected() {
        var missing = fixture();
        row(missing).getAsJsonArray("teleportReturns")
            .add(new JsonPrimitive(true));
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(missing, "none"));
        var mismatched = fixture();
        actual(mismatched).addProperty("distanceSquared", 100);
        JsonObject before = copy(row(mismatched).getAsJsonObject("before"));
        before.addProperty("look", "other");
        teleport(mismatched, false, false, true, before, copy(before));
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(mismatched, "none"));
        row(mismatched).getAsJsonArray("teleportObservations")
            .get(0)
            .getAsJsonObject()
            .remove("walkTargetRetained");
        assertThrows(IllegalArgumentException.class, () -> FollowCoreReplayMain.replay(mismatched, "none"));
    }

    private static JsonObject fixture() {
        return new JsonParser()
            .parse(
                "{\"schema\":\"follow-core-reference-v1\","
                    + "\"contractSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\","
                    + "\"scope\":\"synthetic protocol unit input; not original evidence\",\"cases\":[{"
                    + "\"id\":\"C01_exact_start\",\"rebuild\":0,\"input\":{\"actual\":{"
                    + "\"radius\":8,\"distanceSquared\":36,\"home\":false,\"canBrainMove\":true,"
                    + "\"breathing\":false,\"ownerResolved\":true,\"ownerSpectator\":false,"
                    + "\"ownerDeadOrDying\":false,\"sameLevel\":true,\"ownerUnderwater\":false}},"
                    + "\"before\":{\"walk\":{\"target\":\"none\",\"speed\":null,\"closeEnough\":null},"
                    + "\"look\":\"none\",\"attack\":\"none\",\"path\":\"none\","
                    + "\"targetPos\":\"none\",\"breathing\":false},"
                    + "\"teleportReturns\":[],\"teleportObservations\":[]}]}")
            .getAsJsonObject();
    }

    private static JsonObject row(JsonObject input) {
        return input.getAsJsonArray("cases")
            .get(0)
            .getAsJsonObject();
    }

    private static JsonObject actual(JsonObject input) {
        return row(input).getAsJsonObject("input")
            .getAsJsonObject("actual");
    }

    private static JsonObject normalised(JsonObject replay) {
        return row(replay).getAsJsonObject("normalised");
    }

    private static JsonObject copy(JsonObject json) {
        return new JsonParser().parse(json.toString())
            .getAsJsonObject();
    }

    private static JsonObject walk(String target, Double speed, Integer closeEnough) {
        var walk = new JsonObject();
        walk.addProperty("target", target);
        walk.addProperty("speed", speed);
        walk.addProperty("closeEnough", closeEnough);
        return walk;
    }

    private static void teleport(JsonObject input, boolean result, boolean nativeCall, boolean retained,
        JsonObject before, JsonObject after) {
        var returns = new JsonArray();
        returns.add(new JsonPrimitive(result));
        row(input).add("teleportReturns", returns);
        var observation = new JsonObject();
        observation.addProperty("result", result);
        observation.addProperty("native", nativeCall);
        observation.addProperty("walkTargetRetained", retained);
        observation.add("before", copy(before));
        observation.add("after", copy(after));
        var observations = new JsonArray();
        observations.add(observation);
        row(input).add("teleportObservations", observations);
    }
}
