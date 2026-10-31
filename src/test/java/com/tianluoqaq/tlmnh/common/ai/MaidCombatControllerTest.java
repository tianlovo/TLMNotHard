package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.MaidCombatController.Status;
import com.tianluoqaq.tlmnh.common.ai.MaidFollowController.PathResult;

public class MaidCombatControllerTest {

    @Test
    public void oldActionIsCancelledBeforeCombatCanLookOrNavigate() {
        var actor = new TestActor();
        var controller = new MaidCombatController(actor, 0);
        assertTrue(controller.tick());
        assertEquals("claim,look,path,", actor.events.toString());
        assertEquals(1, controller.getSelections());
    }

    @Test
    public void attacksRespectCooldownRatherThanRunningEveryTick() {
        var actor = new TestActor();
        actor.distance = 1;
        var controller = new MaidCombatController(actor, 0);
        run(controller, 100);
        assertEquals(5, controller.getAttackAttempts());
        assertEquals(5, controller.getSuccessfulAttacks());
        assertEquals(0, controller.getPathRequests());
    }

    @Test
    public void hiddenTargetsCannotBeHitEvenInsideReach() {
        var actor = new TestActor();
        actor.distance = 1;
        actor.visible = false;
        var controller = new MaidCombatController(actor, 0);
        run(controller, 41);
        assertEquals(0, controller.getAttackAttempts());
        assertEquals(Status.TARGET_HIDDEN, controller.getStatus());
        assertTrue(actor.suppressed);
        assertFalse(actor.target);
    }

    @Test
    public void arrivingAtLastSeenPositionWaitsWithoutRepathingAndResumesOnSight() {
        var actor = new TestActor();
        var controller = new MaidCombatController(actor, 0);
        assertTrue(controller.tick());
        assertTrue(actor.path);
        actor.visible = false;
        controller.tick();
        assertEquals(Status.CHASING, controller.getStatus());
        actor.distance = 1;
        run(controller, 20);
        assertEquals(Status.SEARCHING_LAST_SEEN, controller.getStatus());
        assertFalse(actor.path);
        assertEquals(1, controller.getPathRequests());
        assertEquals(0, controller.getAttackAttempts());
        assertEquals(0, actor.preparation);
        actor.visible = true;
        assertTrue(controller.tick());
        assertEquals(Status.ATTACKING, controller.getStatus());
        assertEquals(1, controller.getAttackAttempts());
        assertEquals(1, controller.getSelections());
    }

    @Test
    public void taskAndStateConstraintsPreventAcquisitionAndAttacks() {
        for (var status : new Status[] { Status.HARD_STOP, Status.DISABLED, Status.NO_OWNER, Status.NO_WEAPON,
            Status.RANGED_UNSUPPORTED }) {
            var actor = new TestActor();
            actor.blocked = status;
            var controller = new MaidCombatController(actor, 0);
            run(controller, 100);
            assertEquals(status, controller.getStatus());
            assertEquals(0, controller.getScans());
            assertEquals(0, controller.getPathRequests());
            assertEquals(0, controller.getAttackAttempts());
        }
    }

    @Test
    public void targetLossImmediatelyReleasesTheAction() {
        var actor = new TestActor();
        var controller = new MaidCombatController(actor, 0);
        controller.tick();
        actor.valid = false;
        assertFalse(controller.tick());
        assertEquals(Status.TARGET_LOST, controller.getStatus());
        assertFalse(actor.target);
        assertFalse(actor.path);
        assertEquals(1, actor.releases);
    }

    @Test
    public void failedPathsBackOffThenAbandonRatherThanBlockingFollowForever() {
        var actor = new TestActor();
        actor.pathResult = PathResult.UNLOADED;
        var controller = new MaidCombatController(actor, 0);
        run(controller, 81);
        assertEquals(3, controller.getPathRequests());
        assertEquals(3, controller.getPathFailures());
        assertEquals(PathResult.UNLOADED, controller.getLastPathResult());
        assertTrue(actor.suppressed);
        assertFalse(actor.target);
        assertFalse(controller.tick());
        assertEquals(3, controller.getPathRequests());
    }

    @Test
    public void pathAcceptedWithoutProgressHasABoundedPursuit() {
        var actor = new TestActor();
        var controller = new MaidCombatController(actor, 0);
        run(controller, 201);
        assertEquals(Status.PATH_FAILED, controller.getStatus());
        assertTrue(actor.suppressed);
        assertFalse(actor.path);
        assertFalse(actor.target);
        assertTrue(controller.getPathRequests() <= 20);
        assertTrue(controller.getPathFailures() > 0);
    }

    @Test
    public void breakingWeaponStopsTheNextTickWithoutAnExtraHit() {
        var actor = new TestActor();
        actor.distance = 1;
        actor.breakWeapon = true;
        var controller = new MaidCombatController(actor, 0);
        controller.tick();
        run(controller, 60);
        assertEquals(1, controller.getAttackAttempts());
        assertEquals(Status.NO_WEAPON, controller.getStatus());
        assertFalse(actor.target);
    }

    @Test
    public void retargetingCannotResetAttackCooldown() {
        var actor = new TestActor();
        actor.distance = 1;
        var controller = new MaidCombatController(actor, 0);
        controller.tick();
        actor.valid = false;
        controller.tick();
        actor.valid = true;
        run(controller, 18);
        assertEquals(1, controller.getAttackAttempts());
        controller.tick();
        assertEquals(2, controller.getAttackAttempts());
    }

    @Test
    public void cancelledDamageIsCountedAsAnAttemptWithoutAHit() {
        var actor = new TestActor();
        actor.distance = 1;
        actor.hit = false;
        var controller = new MaidCombatController(actor, 0);
        run(controller, 40);
        assertEquals(2, controller.getAttackAttempts());
        assertEquals(0, controller.getSuccessfulAttacks());
    }

    private static void run(MaidCombatController controller, int ticks) {
        for (int i = 0; i < ticks; i++) controller.tick();
    }

    @Test
    public void preparationIsNotAnAttackAndVisibilityLossDiscardsIt() {
        var actor = new TestActor();
        actor.distance = 1;
        actor.requiredPreparation = 20;
        actor.interval = 2;
        var controller = new MaidCombatController(actor, 0);
        run(controller, 19);
        assertEquals(0, controller.getAttackAttempts());
        assertEquals(Status.PREPARING_ATTACK, controller.getStatus());
        actor.visible = false;
        controller.tick();
        actor.visible = true;
        run(controller, 19);
        assertEquals(0, controller.getAttackAttempts());
        controller.tick();
        assertEquals(1, controller.getAttackAttempts());
        actor.blocked = Status.HARD_STOP;
        controller.tick();
        assertEquals(0, actor.preparation);
        assertFalse(actor.target);
    }

    private static final class TestActor implements MaidCombatController.Actor {

        private final StringBuilder events = new StringBuilder();
        private boolean target, path, suppressed, valid = true, visible = true, hit = true, breakWeapon;
        private double distance = 36;
        private int releases;
        private int preparation, requiredPreparation, interval = MaidCombatController.ATTACK_INTERVAL;
        private Status blocked;
        private PathResult pathResult = PathResult.STARTED;

        @Override
        public Status blockedReason() {
            return blocked;
        }

        @Override
        public boolean hasTarget() {
            return target;
        }

        @Override
        public boolean acquireTarget() {
            target = !suppressed;
            return target;
        }

        @Override
        public boolean targetValid() {
            return valid;
        }

        @Override
        public boolean targetVisible() {
            return visible;
        }

        @Override
        public double distanceSq() {
            return distance;
        }

        @Override
        public double reachSq() {
            return 2;
        }

        @Override
        public void claimControl() {
            events.append("claim,");
        }

        @Override
        public void lookAtTarget() {
            events.append("look,");
        }

        @Override
        public boolean hasPath() {
            return path;
        }

        @Override
        public PathResult approachTarget() {
            events.append("path,");
            path = pathResult == PathResult.STARTED;
            return pathResult;
        }

        @Override
        public void stopNavigation() {
            path = false;
        }

        @Override
        public boolean attack() {
            events.append("attack,");
            if (breakWeapon) blocked = Status.NO_WEAPON;
            return hit;
        }

        @Override
        public boolean prepareAttack() {
            return ++preparation >= requiredPreparation;
        }

        @Override
        public void interruptAttack() {
            preparation = 0;
        }

        @Override
        public int attackInterval() {
            return interval;
        }

        @Override
        public void release(boolean suppressTarget) {
            releases++;
            suppressed = suppressTarget;
            target = path = false;
        }

        @Override
        public double x() {
            return 0;
        }

        @Override
        public double y() {
            return 0;
        }

        @Override
        public double z() {
            return 0;
        }
    }
}
