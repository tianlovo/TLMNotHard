package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.Status;

/** 检查实际决策的指令遵从、滞回、失效记忆、请求预算和卡住恢复 */
public class MaidFollowControllerTest {

    @Test
    public void followUsesSeparateStartAndStopDistances() {
        var actor = new TestActor();
        actor.ownerX = 4;
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        assertEquals(Status.CLOSE_TO_OWNER, controller.getStatus());
        assertEquals(0, actor.requests);
        actor.ownerX = 6;
        controller.tick();
        assertTrue(actor.path);
        actor.ownerX = 4;
        controller.tick();
        assertTrue(actor.path);
        actor.ownerX = 2;
        controller.tick();
        assertFalse(actor.path);
        actor.ownerX = 4;
        controller.tick();
        assertEquals(Status.CLOSE_TO_OWNER, controller.getStatus());
        assertEquals(1, controller.getCancellations());
    }

    @Test
    public void hardConstraintsCancelBeforeLookingOrRequestingAnotherPath() {
        for (var reason : new Status[] { Status.SITTING, Status.RIDING, Status.SLEEPING, Status.LEASHED, Status.DEAD,
            Status.UNTAMED, Status.SUSPENDED }) {
            var actor = new TestActor();
            var controller = new MaidFollowController(actor, 0);
            controller.tick();
            assertTrue(actor.path);
            actor.stop = reason;
            int looks = actor.looks, requests = actor.requests;
            controller.tick();
            assertEquals(reason, controller.getStatus());
            assertFalse(actor.path);
            run(controller, 30);
            assertEquals(looks, actor.looks);
            assertEquals(requests, actor.requests);
        }
    }

    @Test
    public void busyActionReceivesNoFollowNavigationOrLookUpdates() {
        var actor = new TestActor();
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        actor.stop = Status.BUSY;
        controller.tick();
        assertEquals(Status.BUSY, controller.getStatus());
        assertFalse(actor.path);
        int requests = actor.requests, looks = actor.looks;
        run(controller, 30);
        assertEquals(requests, actor.requests);
        assertEquals(looks, actor.looks);
        assertEquals(1, controller.getCancellations());
    }

    @Test
    public void unavailableOwnerStopsBetweenScheduledScans() {
        var actor = new TestActor();
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        actor.ownerAlive = false;
        controller.tick();
        assertEquals(Status.OWNER_UNAVAILABLE, controller.getStatus());
        assertFalse(actor.path);
        assertEquals(1, controller.getOwnerScans());
        assertEquals(1, controller.getPathRequests());
    }

    @Test
    public void movingActorHasBoundedOwnerScansAndPathRequests() {
        var actor = new TestActor();
        actor.ownerX = 30;
        var controller = new MaidFollowController(actor, 0);
        for (int i = 0; i < 100; i++) {
            actor.x += 0.1;
            controller.tick();
        }
        assertEquals(10, controller.getOwnerScans());
        assertEquals(10, controller.getPathRequests());
        assertEquals(0, controller.getStuckRecoveries());
    }

    @Test
    public void failedUnchangedTargetBacksOffRatherThanPathfindingEveryTick() {
        var actor = new TestActor();
        actor.result = PathResult.UNREACHABLE;
        var controller = new MaidFollowController(actor, 0);
        run(controller, 160);
        assertEquals(4, controller.getPathRequests());
        assertEquals(4, controller.getPathFailures());
        assertFalse(actor.path);
    }

    @Test
    public void changedTargetInvalidatesFailureCacheButKeepsMinimumPathInterval() {
        var actor = new TestActor();
        actor.result = PathResult.UNREACHABLE;
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        actor.ownerX += 3;
        actor.result = PathResult.STARTED;
        run(controller, 9);
        assertEquals(1, controller.getPathRequests());
        controller.tick();
        assertEquals(2, controller.getPathRequests());
        assertTrue(actor.path);
    }

    @Test
    public void acceptedPathWithoutMovementIsCancelledAndRetriedAfterBackoff() {
        var actor = new TestActor();
        var controller = new MaidFollowController(actor, 0);
        run(controller, 41);
        assertEquals(Status.STUCK, controller.getStatus());
        assertEquals(1, controller.getStuckRecoveries());
        assertFalse(actor.path);
        long requests = controller.getPathRequests();
        run(controller, 39);
        assertEquals(requests, controller.getPathRequests());
        controller.tick();
        assertEquals(requests + 1, controller.getPathRequests());
        assertTrue(actor.path);
    }

    @Test
    public void unloadedDestinationIsASeparateFailureAndDoesNotBusyLoop() {
        var actor = new TestActor();
        actor.result = PathResult.UNLOADED;
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        assertEquals(Status.DESTINATION_UNLOADED, controller.getStatus());
        run(controller, 39);
        assertEquals(1, controller.getPathRequests());
    }

    @Test
    public void cancellationDoesNotResetTheNavigationBudget() {
        var actor = new TestActor();
        var controller = new MaidFollowController(actor, 0);
        controller.tick();
        for (int i = 0; i < 9; i++) {
            controller.cancel();
            controller.tick();
        }
        assertEquals(1, controller.getPathRequests());
        assertEquals(1, controller.getOwnerScans());
        assertFalse(actor.path);
        controller.tick();
        assertEquals(2, controller.getPathRequests());
        assertEquals(2, controller.getOwnerScans());
    }

    @Test
    public void targetsOutsideLoadedFollowRangeDoNotTriggerNavigation() {
        var actor = new TestActor();
        actor.ownerX = 65;
        var controller = new MaidFollowController(actor, 0);
        run(controller, 30);
        assertEquals(Status.OWNER_TOO_FAR, controller.getStatus());
        assertEquals(0, controller.getPathRequests());
        assertFalse(actor.path);
    }

    private static void run(MaidFollowController controller, int ticks) {
        for (int i = 0; i < ticks; i++) controller.tick();
    }

    private static final class TestActor implements MaidFollowController.Actor {

        private double x, ownerX = 8;
        private boolean sensed, ownerAlive = true, path;
        private int requests, looks;
        private Status stop;
        private PathResult result = PathResult.STARTED;

        @Override
        public Status stopReason() {
            return stop;
        }

        @Override
        public boolean refreshOwner() {
            sensed = ownerAlive;
            return sensed;
        }

        @Override
        public void forgetOwner() {
            sensed = false;
        }

        @Override
        public boolean ownerValid() {
            return sensed && ownerAlive;
        }

        @Override
        public double distanceToOwnerSq() {
            return (ownerX - x) * (ownerX - x);
        }

        @Override
        public double ownerX() {
            return ownerX;
        }

        @Override
        public double ownerY() {
            return 0;
        }

        @Override
        public double ownerZ() {
            return 0;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return 0;
        }

        @Override
        public double z() {
            return 0;
        }

        @Override
        public void lookAtOwner() {
            looks++;
        }

        @Override
        public boolean hasPath() {
            return path;
        }

        @Override
        public PathResult requestPath() {
            requests++;
            path = result == PathResult.STARTED;
            return result;
        }

        @Override
        public void stopNavigation() {
            path = false;
        }
    }
}
