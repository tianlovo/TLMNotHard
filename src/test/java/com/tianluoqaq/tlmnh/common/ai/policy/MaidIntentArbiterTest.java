package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

public class MaidIntentArbiterTest {

    @Test
    public void commandPreemptsMaximumOptionalUtilityWithoutWaitingForMinimumHold() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var arbiter = new MaidIntentArbiter();
        var first = frame(context, 0);
        first.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", 1, null);
        arbiter.choose(first);
        var second = frame(context, 1);
        second.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", 1, null);
        var follow = second.offer(Group.COMMAND, Kind.FOLLOW, "owner", 0, null);
        assertSame(follow, arbiter.choose(second));
        assertEquals(MaidIntentArbiter.Reason.HIGHER_GROUP, arbiter.reason());
    }

    @Test
    public void minimumHoldAndMarginUseCurrentCandidatesRatherThanOldPayloads() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var arbiter = new MaidIntentArbiter();
        var first = frame(context, 0);
        first.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", .4, "old");
        arbiter.choose(first);
        var second = frame(context, 10);
        var current = second.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", .4, "fresh");
        second.offer(Group.AUTONOMOUS, Kind.PICKUP, "drop", .9, null);
        assertSame(current, arbiter.choose(second));
        var third = frame(context, 20);
        third.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", .4, null);
        third.offer(Group.AUTONOMOUS, Kind.PICKUP, "drop", .44, null);
        assertEquals(Kind.SNACK, arbiter.choose(third).kind);
        var fourth = frame(context, 21);
        fourth.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", .4, null);
        var better = fourth.offer(Group.AUTONOMOUS, Kind.PICKUP, "drop", .5, null);
        assertSame(better, arbiter.choose(fourth));
    }

    @Test
    public void missingLegalGoalCannotBeHeldAndEmptyFrameHasNoFallback() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var arbiter = new MaidIntentArbiter();
        var first = frame(context, 0);
        first.offer(Group.CARE, Kind.SLEEP, "bed", 1, null);
        arbiter.choose(first);
        var second = frame(context, 1);
        var pickup = second.offer(Group.AUTONOMOUS, Kind.PICKUP, "drop", .1, null);
        assertSame(pickup, arbiter.choose(second));
        assertNull(arbiter.choose(frame(context, 2)));
    }

    @Test
    public void nextFrameAndLifecycleRevokeOldIntentsWithoutRevokingNewFrame() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var oldToken = context.getToken();
        var first = frame(context, 0);
        var old = first.offer(Group.AUTONOMOUS, Kind.SNACK, "cake", 1, null);
        var second = frame(context, 0);
        assertFalse(first.accepts(old));
        assertFalse(second.accepts(old));
        context.invalidate();
        context.setActive(true);
        var current = frame(context, 1);
        new MaidIntentFrame(context, oldToken, 2);
        assertTrue(current.isCurrent());
        assertFalse(second.isCurrent());
    }

    private static MaidIntentFrame frame(MaidPolicyContext context, long tick) {
        return new MaidIntentFrame(context, context.getToken(), tick);
    }

    @Test
    public void breathingPreemptsDangerDespiteEqualUtilityAndMinimumHold() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var arbiter = new MaidIntentArbiter();
        var first = frame(context, 0);
        first.offer(Group.DANGER, Kind.PANIC, "panic", 1, null);
        arbiter.choose(first);
        var second = frame(context, 1);
        second.offer(Group.DANGER, Kind.PANIC, "panic", 1, null);
        var air = second.offer(Group.EMERGENCY, Kind.BREATHE, "air", 1, null);
        assertSame(air, arbiter.choose(second));
        assertEquals(MaidIntentArbiter.Reason.HIGHER_GROUP, arbiter.reason());
    }
}
