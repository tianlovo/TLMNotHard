package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;

public class MaidActionRunnerTest {

    @Test
    public void transferCleansOldOwnerBeforeGrantAndOldReleaseCannotClearNewOwner() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var command = new Object();
        var runner = new MaidActionRunner();
        runner.beginFrame(context, context.getToken(), world, command);
        var follow = runner.newRun(world, command);
        var events = new StringBuilder();
        assertTrue(runner.claim(Owner.FOLLOW, follow, () -> {
            assertNull(runner.getOwner());
            assertTrue(follow.isCancelled());
            runner.release(follow);
            events.append("cancel_follow,");
        }));
        var combat = runner.newRun(world, command);
        assertTrue(runner.claim(Owner.COMBAT, combat, () -> {}));
        events.append("grant_combat");
        assertEquals("cancel_follow,grant_combat", events.toString());
        runner.release(follow);
        assertTrue(runner.isOwned(combat, world, command));
        assertEquals(Owner.COMBAT, runner.getOwner());
        combat.cancel();
        assertFalse(runner.claim(Owner.LIFE, runner.newRun(world, command), () -> {}));
        runner.release(combat);
        assertTrue(runner.claim(Owner.LIFE, runner.newRun(world, command), () -> {}));
    }

    @Test
    public void reentryOrInvalidationDuringCleanupRefusesThePendingGrant() {
        for (boolean invalidate : new boolean[] { false, true }) {
            var context = new MaidPolicyContext(UUID.randomUUID());
            var world = new Object();
            var command = new Object();
            var runner = new MaidActionRunner();
            runner.beginFrame(context, context.getToken(), world, command);
            assertTrue(runner.claim(Owner.LIFE, runner.newRun(world, command), () -> {
                if (invalidate) context.invalidate();
                else assertFalse(runner.claim(Owner.FOLLOW, runner.newRun(world, command), () -> {}));
            }));
            var pending = runner.newRun(world, command);
            assertFalse(runner.claim(Owner.COMBAT, pending, () -> {}));
            assertTrue(pending.isCancelled());
            assertNull(runner.getOwner());
        }
    }

    @Test
    public void cleanupFailureCannotInstallPendingOwnerAndNewScopeCanRecover() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var command = new Object();
        var runner = new MaidActionRunner();
        runner.beginFrame(context, context.getToken(), world, command);
        assertTrue(
            runner.claim(
                Owner.FOLLOW,
                runner.newRun(world, command),
                () -> { throw new IllegalStateException("fixture cleanup failure"); }));
        var pending = runner.newRun(world, command);
        try {
            runner.claim(Owner.LIFE, pending, () -> {});
            fail("cleanup failure must remain observable");
        } catch (IllegalStateException expected) {
            assertTrue(pending.isCancelled());
        }
        assertNull(runner.getOwner());
        runner.clear();
        context.invalidate();
        context.setActive(true);
        runner.beginFrame(context, context.getToken(), world, command);
        assertFalse(runner.claim(Owner.LIFE, pending, () -> {}));
        assertTrue(runner.claim(Owner.LIFE, runner.newRun(world, command), () -> {}));
    }
}
