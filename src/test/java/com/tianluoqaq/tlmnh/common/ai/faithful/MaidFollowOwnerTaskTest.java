package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner;

public class MaidFollowOwnerTaskTest {

    @Test
    public void defaultRadiusUsesStrictSixAndTenBoundaries() {
        double[] distances = { 5.999, 6, 6.001, 9.999, 10, 10.001 };
        List<List<String>> expected = Arrays.asList(
            Arrays.asList(),
            Arrays.asList("walk"),
            Arrays.asList("walk"),
            Arrays.asList("walk"),
            Arrays.asList("teleport", "reset"),
            Arrays.asList("teleport", "reset"));
        for (int i = 0; i < distances.length; i++) {
            var actor = new TestActor();
            actor.distance = distances[i];
            assertTrue(new MaidFollowOwnerTask(0.5F, 2).tryStart(actor));
            assertEquals("distance=" + distances[i], expected.get(i), actor.events);
        }
    }

    @Test
    public void radiusIsReadEachStartAndTruncatedBeforeSubtractingTwo() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 6;
        actor.radius = 8.99F;
        task.start(actor);
        events(actor, "walk");

        actor.events.clear();
        actor.radius = 12.99F;
        task.start(actor);
        events(actor);

        actor.radius = 3.99F;
        actor.distance = 5;
        task.start(actor);
        events(actor, "teleport", "reset");

        actor.events.clear();
        actor.radius = 32;
        actor.distance = 29.999;
        task.start(actor);
        events(actor);
        actor.distance = 30;
        task.start(actor);
        events(actor, "walk");
        actor.events.clear();
        actor.distance = 34;
        task.start(actor);
        events(actor, "teleport", "reset");
    }

    @Test
    public void startPassesConfiguredMovementParametersToBothTargets() {
        var actor = new TestActor();
        actor.distance = 7;
        new MaidFollowOwnerTask(0.375F, 4).start(actor);
        assertSame(actor.owner, actor.writtenOwner);
        assertEquals(0.375F, actor.writtenSpeed, 0);
        assertEquals(4, actor.writtenStopDistance);
        events(actor, "walk");
    }

    @Test
    public void existingSameOwnerTargetIsPreservedButOtherTargetIsReplaced() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 7;
        actor.walkOwner = actor.owner;
        task.start(actor);
        events(actor);
        actor.walkOwner = new TestOwner(actor.world);
        task.start(actor);
        events(actor, "walk");
        assertSame(actor.owner, actor.writtenOwner);
    }

    @Test
    public void invalidOwnersAreRejectedAndWorldUsesReferenceIdentity() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 20;
        actor.owner = null;
        assertFalse(task.tryStart(actor));
        actor.owner = new TestOwner(actor.world);
        actor.owner.spectator = true;
        assertFalse(task.tryStart(actor));
        actor.owner.spectator = false;
        actor.owner.dead = true;
        assertFalse(task.tryStart(actor));
        actor.owner.dead = false;
        actor.world = new EqualWorld();
        actor.owner.world = new EqualWorld();
        assertEquals(actor.world, actor.owner.world);
        assertFalse(task.tryStart(actor));
        events(actor);
    }

    @Test
    public void ownerIsResolvedAgainAtStartWithoutReusingCheckedOwner() {
        var actor = new TestActor() {

            @Override
            public Owner getOwner() {
                return ++ownerQueries == 1 ? owner : null;
            }
        };
        actor.distance = 20;
        assertTrue(new MaidFollowOwnerTask(0.5F, 2).tryStart(actor));
        assertEquals(2, actor.ownerQueries);
        events(actor);
    }

    @Test
    public void directStartRevalidatesOwnerAndNeverTeleportsAcrossWorlds() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 20;
        assertTrue(task.checkExtraStartConditions(actor));
        actor.owner.world = new Object();
        task.start(actor);
        events(actor);
        assertEquals(2, actor.ownerQueries);
    }

    @Test
    public void homeAndMovementLocksDoNotClearExistingTargets() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 20;
        actor.walkOwner = actor.owner;
        actor.goingToBreath = true;
        actor.home = true;
        assertTrue(task.tryStart(actor));
        actor.home = false;
        actor.canMove = false;
        assertTrue(task.tryStart(actor));
        assertSame(actor.owner, actor.walkOwner);
        assertTrue(actor.goingToBreath);
        events(actor);
    }

    @Test
    public void ordinaryFailedTeleportStillResetsWithoutClearingMemoriesOrWalking() {
        var actor = new TestActor();
        actor.distance = 10;
        actor.walkOwner = actor.owner;
        actor.teleportResults.add(false);
        new MaidFollowOwnerTask(0.5F, 2).start(actor);
        events(actor, "teleport", "reset");
        assertSame(actor.owner, actor.walkOwner);
        assertFalse(actor.taskStopped);
    }

    @Test
    public void underwaterOwnerPreventsBreathingStartButNotOrdinaryStart() {
        var actor = new TestActor();
        var task = new MaidFollowOwnerTask(0.5F, 2);
        actor.distance = 10;
        actor.owner.underWater = true;
        actor.goingToBreath = true;
        assertFalse(task.tryStart(actor));
        events(actor);
        actor.goingToBreath = false;
        assertTrue(task.tryStart(actor));
        events(actor, "teleport", "reset");
    }

    @Test
    public void breathingSuccessAtCloseDistanceClearsOnlyItsExplicitStateInOrder() {
        var actor = new TestActor();
        actor.distance = 1;
        actor.goingToBreath = true;
        actor.walkOwner = actor.owner;
        new MaidFollowOwnerTask(0.5F, 2).tryStart(actor);
        events(actor, "teleport", "reset", "breath:false", "eraseWalk", "eraseTargetPos", "stop");
        assertFalse(actor.goingToBreath);
        assertTrue(actor.taskStopped);
        assertEquals(0, actor.radiusQueries);
    }

    @Test
    public void breathingFailedTeleportFallsThroughAndCanTryAgainAtFarDistance() {
        var actor = new TestActor();
        actor.distance = 10;
        actor.goingToBreath = true;
        actor.teleportResults.add(false);
        actor.teleportResults.add(false);
        new MaidFollowOwnerTask(0.5F, 2).tryStart(actor);
        events(actor, "teleport", "teleport", "reset");
        assertTrue(actor.goingToBreath);
        assertFalse(actor.taskStopped);
    }

    @Test
    public void breathingFailedTeleportCanCreateNormalWalkTargetWithoutResetting() {
        var actor = new TestActor();
        actor.distance = 7;
        actor.goingToBreath = true;
        actor.teleportResults.add(false);
        new MaidFollowOwnerTask(0.5F, 2).tryStart(actor);
        events(actor, "teleport", "walk");
        assertTrue(actor.goingToBreath);
        assertFalse(actor.taskStopped);
    }

    @Test
    public void nativeTeleportFailureExceptionIsNotHiddenBySyntheticResetOrFallback() {
        var failure = new IllegalStateException("native teleport failure");
        var actor = new TestActor() {

            @Override
            public boolean teleportToOwner(Owner owner) {
                events.add("teleport");
                throw failure;
            }
        };
        actor.distance = 20;
        try {
            new MaidFollowOwnerTask(0.5F, 2).start(actor);
            fail("Native failure must reach the lifecycle owner");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
        events(actor, "teleport");
    }

    private static void events(TestActor actor, String... expected) {
        assertEquals(Arrays.asList(expected), actor.events);
    }

    private static class TestActor implements FollowOwnerActor {

        Object world = new Object();
        TestOwner owner = new TestOwner(world);
        final List<String> events = new ArrayList<>();
        final Deque<Boolean> teleportResults = new ArrayDeque<>();
        float radius = 8;
        double distance;
        boolean goingToBreath;
        boolean home;
        boolean canMove = true;
        boolean taskStopped;
        int ownerQueries;
        int radiusQueries;
        Owner walkOwner;
        Owner writtenOwner;
        float writtenSpeed;
        int writtenStopDistance;

        @Override
        public Owner getOwner() {
            ownerQueries++;
            return owner;
        }

        @Override
        public Object getWorldIdentity() {
            return world;
        }

        @Override
        public float getRestrictRadius() {
            radiusQueries++;
            return radius;
        }

        @Override
        public boolean isGoingToBreath() {
            return goingToBreath;
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
        public boolean closerThan(Owner owner, double threshold) {
            return distance * distance < threshold * threshold;
        }

        @Override
        public boolean ownerIsWalkTarget(Owner owner) {
            return walkOwner == owner;
        }

        @Override
        public boolean teleportToOwner(Owner owner) {
            events.add("teleport");
            return teleportResults.isEmpty() || teleportResults.removeFirst();
        }

        @Override
        public void resetNavigation() {
            events.add("reset");
        }

        @Override
        public void setGoingToBreath(boolean goingToBreath) {
            this.goingToBreath = goingToBreath;
            events.add("breath:" + goingToBreath);
        }

        @Override
        public void eraseWalkTarget() {
            walkOwner = null;
            events.add("eraseWalk");
        }

        @Override
        public void eraseTargetPos() {
            events.add("eraseTargetPos");
        }

        @Override
        public void setWalkAndLookTargetMemories(Owner owner, float speedModifier, int stopDistance) {
            writtenOwner = owner;
            writtenSpeed = speedModifier;
            writtenStopDistance = stopDistance;
            events.add("walk");
        }

        @Override
        public void stopFollowingTask() {
            taskStopped = true;
            events.add("stop");
        }
    }

    private static final class TestOwner implements Owner {

        Object world;
        boolean spectator;
        boolean dead;
        boolean underWater;

        TestOwner(Object world) {
            this.world = world;
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
            return underWater;
        }
    }

    private static final class EqualWorld {

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualWorld;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
