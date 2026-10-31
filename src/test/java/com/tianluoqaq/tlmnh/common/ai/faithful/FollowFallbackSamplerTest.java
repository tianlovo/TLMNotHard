package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;

public class FollowFallbackSamplerTest {

    private static final double ANGLE = 1.5707963705062866D;

    @Test
    public void originalUtilityFixtureBindsCandidateOrderSelectionAndFollowingRandomValues() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/fixtures/faithful/follow-fallback-utility-v1.json");
        assertNotNull("Original utility fixture missing", stream);
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader)
                .getAsJsonObject();
        }
        assertEquals(
            "mapped-fallback-utility-v1",
            fixture.get("schema")
                .getAsString());
        assertEquals(
            "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6",
            fixture.get("upstreamCommit")
                .getAsString());
        assertEquals(
            17,
            fixture.get("javaFeature")
                .getAsInt());
        assertFalse(
            fixture.get("postTransformationDefinitionVerified")
                .getAsBoolean());
        JsonArray samples = fixture.getAsJsonArray("directionSamples");
        assertEquals(40, samples.size());
        int attempts = 0;
        for (JsonElement value : samples) {
            JsonObject sample = value.getAsJsonObject();
            Sample actor = new Sample();
            actor.random = new Random(
                Long.parseLong(
                    sample.get("seed")
                        .getAsString()));
            assertEquals(
                0,
                sample.get("yOffset")
                    .getAsInt());
            Vec3 actual = FollowFallbackSampler.getPosTowards(
                actor,
                sample.get("horizontal")
                    .getAsInt(),
                sample.get("vertical")
                    .getAsInt(),
                new Vec3(
                    sample.get("x")
                        .getAsDouble(),
                    0,
                    sample.get("z")
                        .getAsDouble()),
                sample.get("angle")
                    .getAsDouble());
            JsonArray expected = sample.getAsJsonArray("attempts");
            assertEquals(10, expected.size());
            List<JsonArray> accepted = new ArrayList<>();
            for (JsonElement candidate : expected)
                if (!candidate.isJsonNull()) accepted.add(candidate.getAsJsonArray());
            assertEquals(accepted.size(), actor.candidates.size());
            for (int index = 0; index < accepted.size(); index++) {
                JsonArray position = accepted.get(index);
                assertBlock(
                    actor.candidates.get(index),
                    position.get(0)
                        .getAsInt(),
                    position.get(1)
                        .getAsInt(),
                    position.get(2)
                        .getAsInt());
            }
            if (accepted.isEmpty()) assertNull(actual);
            else {
                JsonArray first = accepted.get(0);
                assertPosition(
                    actual,
                    first.get(0)
                        .getAsInt() + .5D,
                    first.get(1)
                        .getAsInt(),
                    first.get(2)
                        .getAsInt() + .5D);
            }
            assertEquals(
                sample.get("nextFloatHex")
                    .getAsString(),
                Float.toHexString(actor.random.nextFloat()));
            assertEquals(
                sample.get("nextDoubleHex")
                    .getAsString(),
                Double.toHexString(actor.random.nextDouble()));
            assertEquals(
                sample.get("nextInt97")
                    .getAsInt(),
                actor.random.nextInt(97));
            attempts += expected.size();
        }
        assertEquals(400, attempts);
    }

    @Test
    public void equalScoresKeepFirstCandidateButStillConsumeAllTenAttempts() {
        Sample actor = new Sample();
        assertPosition(run(actor), .5D, -7, .5D);
        assertEquals(10, actor.scoreCalls);
        assertEquals(30, actor.randomCalls.size());
    }

    @Test
    public void strictlyHigherLateScoreReplacesEarlierCandidate() {
        Sample actor = new Sample();
        actor.scores = new float[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };
        assertPosition(run(actor), .5D, 2, .5D);
        assertEquals(10, actor.scoreCalls);
    }

    @Test
    public void originalScoreComparisonRetainsNanInfinityAndSignedZeroSemantics() {
        for (float score : new float[] { Float.NaN, Float.NEGATIVE_INFINITY }) {
            Sample actor = new Sample();
            actor.scores = new float[] { score };
            assertNull(run(actor));
            assertEquals(10, actor.scoreCalls);
        }
        Sample positiveInfinity = new Sample();
        positiveInfinity.scores = new float[] { Float.POSITIVE_INFINITY };
        assertPosition(run(positiveInfinity), .5D, -7, .5D);
        assertEquals(10, positiveInfinity.scoreCalls);
        Sample zeroTie = new Sample();
        zeroTie.scores = new float[] { -0.0F, 0.0F };
        assertPosition(run(zeroTie), .5D, -7, .5D);
    }

    @Test
    public void rejectedSquareDirectionConsumesNoYIntegerOrWorldFacts() {
        Sample actor = new Sample();
        actor.radial = .999D;
        assertNull(run(actor));
        assertEquals(20, actor.randomCalls.size());
        assertEquals(0, actor.integerCalls);
        assertEquals(0, actor.minHeightCalls);
        assertEquals(0, actor.scoreCalls);
    }

    @Test
    public void buildLimitOrderRetainsInclusiveUpperBoundAndShortCircuitsBelowMinimum() {
        Sample actor = new Sample();
        actor.minimum = 0;
        actor.maximum = 0;
        assertPosition(run(actor), .5D, 0, .5D);
        assertEquals(10, actor.minHeightCalls);
        assertEquals(3, actor.maxHeightCalls);
        assertEquals(1, actor.candidates.size());
        assertEquals(1, actor.scoreCalls);
    }

    @Test
    public void anyNonzeroMalusRejectsBeforeScoringAndNegativeZeroRemainsAllowed() {
        for (float malus : new float[] { -1.0F, Float.NaN }) {
            Sample actor = new Sample();
            actor.malus = malus;
            assertNull(run(actor));
            assertEquals(10, actor.candidates.size());
            assertEquals(0, actor.scoreCalls);
        }
        Sample negativeZero = new Sample();
        negativeZero.malus = -0.0F;
        assertPosition(run(negativeZero), .5D, -7, .5D);
    }

    @Test
    public void restrictionBiasUsesRawCenterAndYThenXThenZRandomOrder() {
        Sample actor = new Sample();
        actor.restriction = true;
        actor.at = new Vec3(.25D, 0, .25D);
        assertPosition(run(actor), -1.5D, -7, -1.5D);
        assertEquals(Arrays.asList("F", "D", "I15", "I5", "I5"), actor.randomCalls.subList(0, 5));
        assertEquals(10, actor.withinCalls);
        Sample farCenter = new Sample();
        farCenter.restriction = true;
        farCenter.center = new BlockPos(1000, 0, 1000);
        assertPosition(run(farCenter), 2.5D, -7, 2.5D);
        assertEquals(0, farCenter.withinCalls);
        assertEquals(30, farCenter.integerCalls);
    }

    @Test
    public void mappedAngleRetainsSignedZeroAndLightScoringRetainsAmbientBlend() {
        assertEquals(
            Double.doubleToRawLongBits(0.0D),
            Double.doubleToRawLongBits(FollowFallbackSampler.mappedAtan2(-0.0D, -0.0D)));
        assertEquals(-.5F, FollowFallbackSampler.lightPathfindingCost(0, 0), 0.0F);
        assertEquals(.5F, FollowFallbackSampler.lightPathfindingCost(15, 0), 0.0F);
        assertEquals(0.0F, FollowFallbackSampler.lightPathfindingCost(0, .5F), 0.0F);
        assertEquals(.5F, FollowFallbackSampler.lightPathfindingCost(15, 1), 0.0F);
    }

    private static Vec3 run(Sample actor) {
        return FollowFallbackSampler
            .getPosTowards(actor, 10, 7, new Vec3(actor.at.x + 1, actor.at.y, actor.at.z), ANGLE);
    }

    private static void assertPosition(Vec3 actual, double x, double y, double z) {
        assertNotNull(actual);
        assertEquals(x, actual.x, 0.0D);
        assertEquals(y, actual.y, 0.0D);
        assertEquals(z, actual.z, 0.0D);
    }

    private static void assertBlock(BlockPos actual, int x, int y, int z) {
        assertEquals(x, actual.x);
        assertEquals(y, actual.y);
        assertEquals(z, actual.z);
    }

    private static final class Sample implements FollowFallbackSampler.Actor {

        final List<String> randomCalls = new ArrayList<>();
        final List<BlockPos> candidates = new ArrayList<>();
        Random random;
        Vec3 at = new Vec3(0, 0, 0);
        BlockPos center = new BlockPos(0, 0, 0);
        boolean restriction;
        int minimum = Integer.MIN_VALUE, maximum = Integer.MAX_VALUE;
        int integerCalls, yCalls, minHeightCalls, maxHeightCalls, withinCalls, scoreCalls;
        double radial;
        float malus;
        float[] scores = { .5F };

        public Vec3 position() {
            return at;
        }

        public float nextEntityFloat() {
            randomCalls.add("F");
            return random == null ? .5F : random.nextFloat();
        }

        public double nextEntityDouble() {
            randomCalls.add("D");
            return random == null ? radial : random.nextDouble();
        }

        public int nextEntityInt(int bound) {
            randomCalls.add("I" + bound);
            integerCalls++;
            return random != null ? random.nextInt(bound) : bound == 15 ? yCalls++ % bound : 2 % bound;
        }

        public boolean hasRestriction() {
            return restriction;
        }

        public BlockPos restrictionCenter() {
            return center;
        }

        public float restrictionRadius() {
            return 8.0F;
        }

        public boolean isWithinRestriction(BlockPos position) {
            withinCalls++;
            return true;
        }

        public int minBuildHeight() {
            minHeightCalls++;
            return minimum;
        }

        public int maxBuildHeight() {
            maxHeightCalls++;
            return maximum;
        }

        public boolean isStableDestination(BlockPos position) {
            candidates.add(position);
            return true;
        }

        public float pathfindingMalus(BlockPos position) {
            return malus;
        }

        public float walkTargetValue(BlockPos position) {
            return scores[Math.min(scoreCalls++, scores.length - 1)];
        }
    }
}
