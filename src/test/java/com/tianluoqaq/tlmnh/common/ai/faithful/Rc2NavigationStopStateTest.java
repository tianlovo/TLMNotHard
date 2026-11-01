package com.tianluoqaq.tlmnh.common.ai.faithful;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

public class Rc2NavigationStopStateTest {

    @Test
    public void completedNativeCallOnlyMatchesTheOwnedInstallationAndExactFrame() {
        var runtime = runtime();
        var context = runtime.getContext();
        var state = new NativeNavigationStopState();
        Object owner = new Object(), path = new Object(), world = new Object();
        var token = context.getToken();
        long generation = state.arm(owner, path, world, context, token);
        state.beforeWatchdogClear(path, world, 30, 101);
        assertFalse(state.matches(owner, path, world, generation, token, 30, 101));
        state.afterWatchdogClear(null, world, 30, 101);
        assertTrue(state.matches(owner, path, world, generation, token, 30, 101));
        assertFalse(state.matches(owner, path, world, generation, token, 30, 102));
        assertFalse(state.matches(owner, new Object(), world, generation, token, 30, 101));
        assertEquals("NATIVE_NAVIGATION_WATCHDOG", state.peekEvent().cause);
    }

    @Test
    public void plainClearAndReinstallationCannotReuseAnEarlierCause() {
        var context = runtime().getContext();
        var state = new NativeNavigationStopState();
        Object owner = new Object(), path = new Object(), world = new Object();
        var token = context.getToken();
        long first = state.arm(owner, path, world, context, token);
        state.afterWatchdogClear(null, world, 30, 101);
        assertNull(state.peekEvent());
        state.beforeWatchdogClear(path, world, 30, 101);
        state.afterWatchdogClear(path, world, 30, 101);
        assertNull(state.peekEvent());
        state.beforeWatchdogClear(path, world, 30, 101);
        state.afterWatchdogClear(null, world, 30, 101);
        long next = state.arm(owner, path, world, context, token);
        assertTrue(next > first);
        assertFalse(state.matches(owner, path, world, first, token, 30, 101));
        assertNull(state.peekEvent());
    }

    @Test
    public void retiredLeaseAndForeignReleaseCannotAuthorizeOrEraseAnotherOwner() {
        var runtime = runtime();
        var context = runtime.getContext();
        var state = new NativeNavigationStopState();
        Object owner = new Object(), path = new Object(), world = new Object();
        var token = context.getToken();
        state.arm(owner, path, world, context, token);
        state.release(new Object());
        assertTrue(state.isObserving());
        state.beforeWatchdogClear(path, world, 30, 101);
        runtime.invalidate(MaidPolicyRuntime.InvalidationReason.RESET);
        state.afterWatchdogClear(null, world, 30, 101);
        assertNull(state.peekEvent());
        assertFalse(state.isObserving());
        state.release(owner);
        assertNull(state.peekEvent());
    }

    private static MaidPolicyRuntime runtime() {
        return new MaidPolicyRuntime(UUID.randomUUID(), new MaidPolicyRuntime.LegacyActions() {

            public boolean tickCombat() {
                return false;
            }

            public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
                return false;
            }

            public void tickFollow() {}

            public void cancelAll() {}
        });
    }
}
