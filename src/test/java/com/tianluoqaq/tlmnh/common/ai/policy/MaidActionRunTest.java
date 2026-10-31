package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class MaidActionRunTest {

    @Test
    public void commitCanBeEnteredOnceAndCancellationCannotBeReportedAsSuccess() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var command = new Object();
        var run = new MaidActionRun(context, context.getToken(), world, command);
        assertTrue(run.tryCommit(world, command));
        assertFalse(run.tryCommit(world, command));
        run.cancel();
        assertFalse(run.finishCommit(true));
        assertTrue(run.isCancelled());
        var next = new MaidActionRun(context, context.getToken(), world, command);
        assertTrue(next.tryCommit(world, command));
        assertTrue(next.finishCommit(true));
        assertFalse(next.tryCommit(world, command));
        assertFalse(next.finishCommit(true));
        run.cancel();
        assertTrue(next.isCurrent(world, command));
    }

    @Test
    public void invocationIdentityWorldAndCommandCannotBeRebound() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var command = new Object();
        var old = context.getToken();
        var run = new MaidActionRun(context, old, world, command);
        assertFalse(run.tryCommit(new Object(), command));
        assertFalse(run.tryCommit(world, new Object()));
        assertTrue(run.matches(context, old));
        context.invalidate();
        context.setActive(true);
        assertFalse(run.isCurrent(world, command));
        assertFalse(run.matches(context, context.getToken()));
        assertFalse(run.tryCommit(world, command));
    }
}
