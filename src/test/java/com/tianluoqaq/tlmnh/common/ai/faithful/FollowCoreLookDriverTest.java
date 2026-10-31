package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowCoreLookDriver.Status;

public class FollowCoreLookDriverTest {

    @Test
    public void missingOrUnregisteredMemoryDoesNotConsumeSharedWorldRandom() {
        var actor = new TestActor();
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        actor.registered = false;
        assertFalse(driver.tryStart(actor, state, 100));
        actor.registered = true;
        actor.target = null;
        assertFalse(driver.tryStart(actor, state, 100));
        assertEquals(Status.STOPPED, state.getStatus());
        assertTrue(actor.calls.isEmpty());
    }

    @Test
    public void invisiblePresentTargetStartsAndDrawsDurationBeforeSamePassStop() {
        var actor = new TestActor();
        actor.visible = false;
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        assertTrue(driver.tryStart(actor, state, 100));
        assertEquals(Status.RUNNING, state.getStatus());
        assertEquals(145, state.getEndTimestamp());
        driver.tickOrStop(actor, state, 100);
        assertEquals(Arrays.asList("world:46", "visible:owner", "erase"), actor.calls);
        assertEquals(Status.STOPPED, state.getStatus());
        assertNull(actor.target);
    }

    @Test
    public void deadlineEqualityEmitsAndNextTickErasesWithoutClearingCommittedNativeRequest() {
        var actor = new TestActor();
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        assertTrue(driver.tryStart(actor, state, 100));
        actor.calls.clear();
        driver.tickOrStop(actor, state, 145);
        assertEquals("owner", actor.committedRequest);
        assertEquals(Status.RUNNING, state.getStatus());
        driver.tickOrStop(actor, state, 146);
        assertEquals(Arrays.asList("visible:owner", "look:owner", "erase"), actor.calls);
        assertEquals("owner", actor.committedRequest);
        assertEquals(145, state.getEndTimestamp());
        assertEquals(Status.STOPPED, state.getStatus());
    }

    @Test
    public void maximumDurationUsesInclusiveNinetyAndKeepsOriginalRandomBound() {
        var actor = new TestActor();
        actor.nextInteger = 45;
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        assertTrue(driver.tryStart(actor, state, 500));
        assertEquals(590, state.getEndTimestamp());
        driver.tickOrStop(actor, state, 590);
        assertEquals(Status.RUNNING, state.getStatus());
        driver.tickOrStop(actor, state, 591);
        assertEquals(Status.STOPPED, state.getStatus());
        assertEquals("world:46", actor.calls.get(0));
    }

    @Test
    public void runningPassConsumesCurrentMemoryRatherThanTheTargetSeenDuringStart() {
        var actor = new TestActor();
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        driver.tryStart(actor, state, 10);
        actor.target = "replacement";
        driver.tickOrStop(actor, state, 10);
        assertEquals("replacement", actor.committedRequest);
        actor.target = null;
        driver.tickOrStop(actor, state, 11);
        assertEquals(Status.STOPPED, state.getStatus());
        assertEquals("replacement", actor.committedRequest);
    }

    @Test
    public void stopDoesNotOverwriteAnExternalNativeLookRequest() {
        var actor = new TestActor();
        var state = new FollowCoreLookDriver.State();
        var driver = new FollowCoreLookDriver();
        driver.tryStart(actor, state, 0);
        driver.tickOrStop(actor, state, 0);
        actor.committedRequest = "external";
        actor.calls.clear();
        driver.doStop(actor, state, 1);
        assertEquals(Arrays.asList("erase"), actor.calls);
        assertEquals("external", actor.committedRequest);
    }

    private static final class TestActor implements FollowCoreLookDriver.Actor {

        boolean registered = true, visible = true;
        String target = "owner", committedRequest;
        int nextInteger;
        final List<String> calls = new ArrayList<>();

        public boolean isLookTargetRegistered() {
            return registered;
        }

        public boolean hasLookTarget() {
            return target != null;
        }

        public boolean isCurrentLookTargetVisible() {
            calls.add("visible:" + target);
            return target != null && visible;
        }

        public void lookAtCurrentTargetIfPresent() {
            if (target != null) {
                calls.add("look:" + target);
                committedRequest = target;
            }
        }

        public void eraseLookTarget() {
            calls.add("erase");
            target = null;
        }

        public int nextWorldInt(int bound) {
            calls.add("world:" + bound);
            return nextInteger;
        }
    }
}
