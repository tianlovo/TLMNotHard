package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Path;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState.Status;

public class FollowCoreMoveDriverTest {

    @Test
    public void blockMetricPreservesModernFloatAccumulationAndIntegerCornerDistance() {
        var origin = new BlockPos(0, 0, 0);
        assertEquals(16_777_216, origin.manhattanDistance(new BlockPos(16_777_216, 1, 0)));
        assertEquals(4, origin.distanceSquared(new BlockPos(2, 0, 0)), 0);
        assertEquals(5, origin.distanceSquared(new BlockPos(2, 0, 1)), 0);
    }

    @Test
    public void createPrecedesDurationAndInstallationWhichIgnoresMoveReturn() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        actor.randoms.add(100);
        actor.moveAccepted = false;
        actor.onMove = () -> assertEquals(Status.RUNNING, state.getStatus());
        assertTrue(new FollowCoreMoveDriver().tryStart(actor, state, 70));
        events(actor, "create:block", "cant:erase", "world:101", "path:p1", "move:p1");
        assertEquals(320, state.getEndTimestamp());
        assertSame(actor.defaultPath, state.getPath());
        assertSame(actor.defaultPath, actor.pathMemory);
        assertEquals(0.5F, state.getSpeedModifier(), 0);
    }

    @Test
    public void onlyEligibleStartChecksDecrementCooldown() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        state.remainingCooldown = 1;
        actor.walk = null;
        assertFalse(driver.tryStart(actor, state, 0));
        assertEquals(1, state.getRemainingCooldown());
        actor.walk = new Target();
        actor.pathMemory = actor.defaultPath;
        assertFalse(driver.tryStart(actor, state, 0));
        assertEquals(1, state.getRemainingCooldown());
        actor.pathMemory = null;
        actor.registered = false;
        assertFalse(driver.tryStart(actor, state, 0));
        assertEquals(1, state.getRemainingCooldown());
        actor.registered = true;
        assertFalse(driver.tryStart(actor, state, 0));
        assertEquals(0, state.getRemainingCooldown());
        events(actor);
        assertTrue(driver.tryStart(actor, state, 1));
    }

    @Test
    public void alreadyReachedUsesThreeDimensionalManhattanAndErasesFirstFailure() {
        var actor = new TestActor();
        var driver = new FollowCoreMoveDriver();
        actor.walk.position = new BlockPos(1, 0, 1);
        actor.cantReach = 8L;
        assertFalse(driver.tryStart(actor, new FollowMoveState(), 10));
        events(actor, "walk:erase", "cant:erase");
        assertNull(actor.cantReach);
        actor.events.clear();
        actor.walk = new Target();
        actor.walk.position = new BlockPos(2, 0, 1);
        assertTrue(driver.tryStart(actor, new FollowMoveState(), 10));
        assertEquals("create:block", actor.events.get(0));
    }

    @Test
    public void deadlineEqualityStillTicksAndFollowingTickStopsWithoutStuckRandom() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 100);
        assertEquals(250, state.getEndTimestamp());
        actor.events.clear();
        driver.tickOrStop(actor, state, 250);
        events(actor);
        assertEquals(Status.RUNNING, state.getStatus());
        driver.tickOrStop(actor, state, 251);
        events(actor, "stop", "walk:erase", "path:none");
        assertEquals(Status.STOPPED, state.getStatus());
    }

    @Test
    public void replanRequiresTargetBlockDistanceSquaredStrictlyGreaterThanFour() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 0);
        actor.events.clear();
        actor.walk.position = new BlockPos(7, 0, 0);
        driver.tickOrStop(actor, state, 1);
        events(actor);
        actor.walk.position = new BlockPos(7, 0, 1);
        actor.walk.speed = 0.75F;
        driver.tickOrStop(actor, state, 2);
        events(actor, "create:block", "cant:erase", "path:p1", "move:p1");
        assertEquals(7, state.getLastTargetPos().x);
        assertEquals(1, state.getLastTargetPos().z);
        assertEquals(0.75F, state.getSpeedModifier(), 0);
        assertEquals(150, state.getEndTimestamp());
    }

    @Test
    public void partialPathRunsWithoutFallbackAndPreservesFirstWorldFailureTime() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        actor.defaultPath.reachable = false;
        actor.worldTime = 900;
        assertTrue(driver.tryStart(actor, state, 100));
        assertEquals(Long.valueOf(900), actor.cantReach);
        events(actor, "create:block", "cant:900", "world:101", "path:p1", "move:p1");
        driver.doStop(actor, state, 101);
        actor.events.clear();
        actor.walk = new Target();
        actor.worldTime = 999;
        assertTrue(driver.tryStart(actor, state, 102));
        assertEquals(Long.valueOf(900), actor.cantReach);
        events(actor, "create:block", "world:101", "path:p1", "move:p1");
    }

    @Test
    public void nullPathUsesExactFallbackArgumentsAndDoesNotRecheckFallbackReachability() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        actor.queries.add(null);
        actor.queries.add(new TestPath("fallback", true));
        actor.fallback = new Vec3(3.25, 1, 2.75);
        actor.worldTime = 123;
        assertTrue(new FollowCoreMoveDriver().tryStart(actor, state, 1));
        events(
            actor,
            "create:block",
            "cant:123",
            "fallback",
            "create:coordinate",
            "world:101",
            "path:fallback",
            "move:fallback");
        assertEquals(10, actor.fallbackHorizontal);
        assertEquals(7, actor.fallbackVertical);
        assertEquals(5.5, actor.fallbackTarget.x, 0);
        assertEquals(0, actor.fallbackTarget.y, 0);
        assertEquals(0.5, actor.fallbackTarget.z, 0);
        assertEquals(1.5707963705062866, actor.fallbackAngle, 0);
        assertEquals(Long.valueOf(123), actor.cantReach);
    }

    @Test
    public void nullFallbackOrSecondNullPathErasesWalkButRetainsCantReach() {
        for (boolean hasFallback : new boolean[] { false, true }) {
            var actor = new TestActor();
            var state = new FollowMoveState();
            actor.queries.add(null);
            if (hasFallback) {
                actor.fallback = new Vec3(1, 0, 1);
                actor.queries.add(null);
            }
            actor.worldTime = 19;
            assertFalse(new FollowCoreMoveDriver().tryStart(actor, state, 0));
            assertEquals(Long.valueOf(19), actor.cantReach);
            assertNull(actor.walk);
            assertNull(actor.pathMemory);
            assertEquals(Status.STOPPED, state.getStatus());
            assertFalse(actor.events.contains("world:101"));
            assertFalse(actor.events.contains("stop"));
        }
    }

    @Test
    public void arrivalDuringCreateClearsCantReachAndRefusesStartEvenWithNonNullPath() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        actor.cantReach = 1L;
        actor.onCreate = () -> actor.position = actor.walk.position;
        assertFalse(new FollowCoreMoveDriver().tryStart(actor, state, 0));
        events(actor, "create:block", "cant:erase", "walk:erase");
        assertSame(actor.defaultPath, state.getPath());
        assertNull(actor.pathMemory);
        assertNull(state.getLastTargetPos());
    }

    @Test
    public void failedReplanKeepsNativePathUntilNextRunningPassStops() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 0);
        actor.events.clear();
        actor.walk.position = new BlockPos(8, 0, 0);
        actor.queries.add(null);
        driver.tickOrStop(actor, state, 1);
        events(actor, "create:block", "cant:0", "fallback");
        assertNull(state.getPath());
        assertSame(actor.defaultPath, actor.navPath);
        assertSame(actor.defaultPath, actor.pathMemory);
        assertEquals(Status.RUNNING, state.getStatus());
        assertEquals(5, state.getLastTargetPos().x);
        actor.events.clear();
        driver.tickOrStop(actor, state, 2);
        events(actor, "stop", "walk:erase", "path:none");
    }

    @Test
    public void runningTickAdoptsChangedNativePathButNotFreshWrapperForSameIdentity() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 0);
        actor.events.clear();
        var wrapped = new TestPath("wrapped", true);
        wrapped.identity = actor.defaultPath.identity;
        actor.navPath = wrapped;
        driver.tickOrStop(actor, state, 1);
        events(actor);
        assertSame(actor.defaultPath, state.getPath());
        var replacement = new TestPath("replacement", false);
        actor.navPath = replacement;
        driver.tickOrStop(actor, state, 2);
        events(actor, "path:replacement");
        assertSame(replacement, state.getPath());
        assertSame(replacement, actor.pathMemory);
    }

    @Test
    public void vanishedNativePathSynchronisesNullBeforeStoppingOnFollowingPass() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 0);
        actor.events.clear();
        actor.navPath = null;
        driver.tickOrStop(actor, state, 1);
        events(actor, "path:none");
        assertEquals(Status.RUNNING, state.getStatus());
        driver.tickOrStop(actor, state, 2);
        events(actor, "path:none", "stop", "walk:erase", "path:none");
    }

    @Test
    public void onlyUnreachedExistingWalkAndActualStuckConsumeCooldownRandom() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        driver.tryStart(actor, state, 0);
        actor.events.clear();
        actor.stuck = true;
        actor.randoms.add(17);
        actor.cantReach = 22L;
        actor.onStop = () -> assertEquals(Status.STOPPED, state.getStatus());
        driver.doStop(actor, state, 1);
        events(actor, "world:40", "stop", "walk:erase", "path:none");
        assertEquals(17, state.getRemainingCooldown());
        assertEquals(Long.valueOf(22), actor.cantReach);
        assertEquals(5, state.getLastTargetPos().x);
        assertEquals(0.5F, state.getSpeedModifier(), 0);
        assertEquals(150, state.getEndTimestamp());
        assertNull(state.getPath());
    }

    @Test
    public void ordinaryDoneAndClearedWalkDoNotMasqueradeAsStuck() {
        for (boolean clearWalk : new boolean[] { false, true }) {
            var actor = new TestActor();
            var state = new FollowMoveState();
            var driver = new FollowCoreMoveDriver();
            driver.tryStart(actor, state, 0);
            actor.events.clear();
            actor.done = true;
            if (clearWalk) {
                actor.walk = null;
                actor.stuck = true;
            }
            driver.tickOrStop(actor, state, 1);
            events(actor, "stop", "walk:erase", "path:none");
            assertEquals(0, state.getRemainingCooldown());
        }
    }

    @Test
    public void spectatorTargetStartsThenFailsRunningConditionsLikeOriginalSink() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        var driver = new FollowCoreMoveDriver();
        actor.walk.spectator = true;
        assertTrue(driver.tryStart(actor, state, 0));
        actor.events.clear();
        driver.tickOrStop(actor, state, 0);
        events(actor, "stop", "walk:erase", "path:none");
    }

    @Test
    public void dependencyFailurePropagatesWithoutFallbackSuccessOrInstallation() {
        var failure = new IllegalStateException("unsupported modern fallback");
        var actor = new TestActor() {

            @Override
            public Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle) {
                events.add("fallback");
                throw failure;
            }
        };
        actor.queries.add(null);
        var state = new FollowMoveState();
        assertSame(
            failure,
            assertThrows(IllegalStateException.class, () -> new FollowCoreMoveDriver().tryStart(actor, state, 0)));
        events(actor, "create:block", "cant:0", "fallback");
        assertNull(actor.navPath);
        assertNull(actor.pathMemory);
        assertEquals(Status.STOPPED, state.getStatus());
    }

    @Test
    public void outOfRangeWorldRandomIsRejectedRatherThanClamped() {
        var actor = new TestActor();
        var state = new FollowMoveState();
        actor.randoms.add(101);
        assertThrows(IllegalArgumentException.class, () -> new FollowCoreMoveDriver().tryStart(actor, state, 0));
        events(actor, "create:block", "cant:erase", "world:101");
        assertNull(actor.pathMemory);
        assertNull(actor.navPath);
    }

    private static void events(TestActor actor, String... expected) {
        assertEquals(Arrays.asList(expected), actor.events);
    }

    private static final class Target implements WalkTarget {

        BlockPos position = new BlockPos(5, 0, 0);
        float speed = 0.5F;
        int closeEnough = 2;
        boolean spectator;

        @Override
        public BlockPos currentBlockPosition() {
            return position;
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
            return spectator;
        }
    }

    private static final class TestPath implements Path {

        final String label;
        Object identity = new Object();
        boolean reachable;

        TestPath(String label, boolean reachable) {
            this.label = label;
            this.reachable = reachable;
        }

        @Override
        public Object getIdentity() {
            return identity;
        }

        @Override
        public boolean canReach() {
            return reachable;
        }
    }

    private static class TestActor implements FollowMoveActor {

        final List<String> events = new ArrayList<>();
        final List<Path> queries = new ArrayList<>();
        final List<Integer> randoms = new ArrayList<>();
        final TestPath defaultPath = new TestPath("p1", true);
        Target walk = new Target();
        Path pathMemory;
        Path navPath;
        Long cantReach;
        BlockPos position = new BlockPos(0, 0, 0);
        long worldTime;
        boolean registered = true, done, stuck, moveAccepted = true;
        Vec3 fallback, fallbackTarget;
        int fallbackHorizontal, fallbackVertical;
        double fallbackAngle;
        Runnable onCreate, onMove, onStop;

        @Override
        public boolean isWalkTargetRegistered() {
            return registered;
        }

        @Override
        public boolean isPathRegistered() {
            return registered;
        }

        @Override
        public boolean isCantReachSinceRegistered() {
            return registered;
        }

        @Override
        public WalkTarget getWalkTarget() {
            return walk;
        }

        @Override
        public Path getPathMemory() {
            return pathMemory;
        }

        @Override
        public Long getCantReachSince() {
            return cantReach;
        }

        @Override
        public void eraseWalkTarget() {
            events.add("walk:erase");
            walk = null;
        }

        @Override
        public void setPathMemory(Path path) {
            events.add("path:" + label(path));
            pathMemory = path;
        }

        @Override
        public void eraseCantReachSince() {
            events.add("cant:erase");
            cantReach = null;
        }

        @Override
        public void setCantReachSince(long gameTime) {
            events.add("cant:" + gameTime);
            cantReach = gameTime;
        }

        @Override
        public BlockPos blockPosition() {
            return position;
        }

        @Override
        public long worldGameTime() {
            return worldTime;
        }

        @Override
        public int nextWorldInt(int bound) {
            events.add("world:" + bound);
            return randoms.isEmpty() ? 0 : randoms.remove(0);
        }

        @Override
        public Path createPath(BlockPos target, int accuracy) {
            assertEquals(0, accuracy);
            events.add("create:block");
            Path path = queries.isEmpty() ? defaultPath : queries.remove(0);
            if (onCreate != null) onCreate.run();
            return path;
        }

        @Override
        public Path createPath(Vec3 target, int accuracy) {
            assertEquals(0, accuracy);
            events.add("create:coordinate");
            return queries.isEmpty() ? defaultPath : queries.remove(0);
        }

        @Override
        public Vec3 findFallbackTowards(int horizontalRange, int verticalRange, Vec3 target, double angle) {
            events.add("fallback");
            fallbackHorizontal = horizontalRange;
            fallbackVertical = verticalRange;
            fallbackTarget = target;
            fallbackAngle = angle;
            return fallback;
        }

        @Override
        public Path navigationPath() {
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
        public boolean moveTo(Path path, double speedModifier) {
            events.add("move:" + label(path));
            navPath = path;
            if (onMove != null) onMove.run();
            return moveAccepted;
        }

        @Override
        public void stopNavigation() {
            events.add("stop");
            navPath = null;
            if (onStop != null) onStop.run();
        }

        private static String label(Path path) {
            return path == null ? "none" : ((TestPath) path).label;
        }
    }
}
