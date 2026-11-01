package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.SelectionResult;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.TickResult;

public class MaidAfterNavigationTest {

    @Test
    public void afterLivingPhaseCannotRunLegacyAndUsesDistinctComponentHook() {
        var sample = new Sample(true);
        assertEquals(TickResult.WRONG_PHASE, sample.runtime.tickAfterLivingUpdate());
        assertTrue(sample.events.isEmpty());
        sample.runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT);
        sample.events.clear();
        assertEquals(TickResult.COMPLETED, sample.runtime.tickAfterNativeNavigation());
        assertEquals(TickResult.COMPLETED, sample.runtime.tickAfterLivingUpdate());
        assertEquals(Arrays.asList("component", "after_living"), sample.events);
    }

    @Test
    public void afterLivingReentryInvalidatesLeaseAndCannotFallThrough() {
        var sample = selected();
        sample.onAfter = () -> assertEquals(TickResult.REFUSED_REENTRANT, sample.runtime.tickAfterLivingUpdate());
        assertEquals(TickResult.INVALIDATED, sample.runtime.tickAfterLivingUpdate());
        assertEquals(Arrays.asList("after_living", "legacy_cancel", "component_cancel"), sample.events);
    }

    @Test
    public void absentComponentCannotFallbackToLegacyAtEitherPhase() {
        var sample = new Sample(false);
        assertEquals(SelectionResult.UNAVAILABLE, sample.runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT));
        assertEquals(TickResult.UNAVAILABLE, sample.runtime.tick(() -> {
            fail();
            return true;
        }));
        assertEquals(TickResult.UNAVAILABLE, sample.runtime.tickAfterNativeNavigation());
        assertEquals(Arrays.asList("legacy_cancel"), sample.events);
    }

    @Test
    public void componentRunsOnlyAfterNavigationWithoutConsultingLegacyPermission() {
        var sample = new Sample(true);
        assertEquals(SelectionResult.SELECTED, sample.runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT));
        sample.events.clear();
        assertEquals(TickResult.WRONG_PHASE, sample.runtime.tick(() -> {
            fail();
            return true;
        }));
        assertEquals(TickResult.COMPLETED, sample.runtime.tickAfterNativeNavigation());
        assertEquals(Arrays.asList("component"), sample.events);
        assertTrue(
            sample.runtime.getProfile()
                .isDevelopmentOnly());
        assertFalse(MaidBrainProfile.FAITHFUL_TLM.isAvailable());
        assertFalse(MaidBrainProfile.ENHANCED.isAvailable());
    }

    @Test
    public void afterHookCannotRunLegacyTwice() {
        var sample = new Sample(true);
        assertEquals(TickResult.WRONG_PHASE, sample.runtime.tickAfterNativeNavigation());
        assertTrue(sample.events.isEmpty());
        assertEquals(TickResult.COMPLETED, sample.runtime.tick(() -> true));
        assertEquals(Arrays.asList("combat", "life", "follow"), sample.events);
    }

    @Test
    public void bothCleanupCallbacksSeeOldTokenAlreadyInvalid() {
        var sample = selected();
        var old = sample.runtime.getContext()
            .getToken();
        sample.onCancel = () -> assertFalse(
            sample.runtime.getContext()
                .isCurrent(old));
        sample.runtime.invalidate(InvalidationReason.OWNER_CHANGED);
        assertEquals(Arrays.asList("legacy_cancel", "component_cancel"), sample.events);
        assertTrue(sample.runtime.isReady());
    }

    @Test
    public void componentInvalidationCannotFallThroughToLegacy() {
        var sample = selected();
        sample.onTick = () -> sample.runtime.invalidate(InvalidationReason.RESET);
        assertEquals(TickResult.INVALIDATED, sample.runtime.tickAfterNativeNavigation());
        assertEquals(Arrays.asList("component", "legacy_cancel", "component_cancel"), sample.events);
    }

    @Test
    public void componentReentryClosesTheCurrentLease() {
        var sample = selected();
        sample.onTick = () -> assertEquals(TickResult.REFUSED_REENTRANT, sample.runtime.tickAfterNativeNavigation());
        assertEquals(TickResult.INVALIDATED, sample.runtime.tickAfterNativeNavigation());
        assertFalse(sample.runtime.isReady());
        assertEquals(TickResult.FAULTED, sample.runtime.tickAfterNativeNavigation());
    }

    @Test
    public void failedLegacyCleanupStillAttemptsComponentCleanup() {
        var sample = selected();
        sample.failLegacyCancel = true;
        sample.failComponentCancel = true;
        try {
            sample.runtime.invalidate(InvalidationReason.RESET);
            fail();
        } catch (IllegalStateException failure) {
            assertEquals("legacy failure", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
        }
        assertEquals(Arrays.asList("legacy_cancel", "component_cancel"), sample.events);
        assertFalse(sample.runtime.isReady());
    }

    @Test
    public void retirePermanentlyRefusesAfterNavigationCallback() {
        var sample = selected();
        sample.runtime.retire(InvalidationReason.UNLOADED);
        sample.events.clear();
        assertEquals(TickResult.RETIRED, sample.runtime.tickAfterNativeNavigation());
        assertTrue(sample.events.isEmpty());
    }

    @Test
    public void bookingIsConsumedOnceAndDuplicateBookingCannotReopenIt() {
        var sample = selected();
        var gate = new MaidAfterNavigationGate();
        var world = new Object();
        assertFalse(gate.consume(world, 1, 1, sample.runtime));
        assertTrue(gate.book(world, 1, 1, sample.runtime));
        assertFalse(gate.book(world, 1, 1, sample.runtime));
        assertTrue(gate.consume(world, 1, 1, sample.runtime));
        assertFalse(gate.consume(world, 1, 1, sample.runtime));
        gate.clear();
        assertFalse(gate.book(world, 1, 1, sample.runtime));
        assertTrue(gate.book(world, 2, 2, sample.runtime));
        assertTrue(gate.consume(world, 2, 2, sample.runtime));
    }

    @Test
    public void invalidatedTokenCannotBeConsumedOrRebookedInSameTick() {
        var sample = selected();
        var gate = new MaidAfterNavigationGate();
        var world = new Object();
        assertTrue(gate.book(world, 1, 1, sample.runtime));
        sample.runtime.invalidate(InvalidationReason.SUSPENSION_CHANGED);
        assertFalse(gate.consume(world, 1, 1, sample.runtime));
        assertFalse(gate.book(world, 1, 1, sample.runtime));
    }

    @Test
    public void everyClockAndIdentityMustMatchAndMismatchConsumesPendingBooking() {
        var sample = selected();
        var world = new Object();
        for (int kind = 0; kind < 4; kind++) {
            var gate = new MaidAfterNavigationGate();
            assertTrue(gate.book(world, 20, 30, sample.runtime));
            assertFalse(
                gate.consume(
                    kind == 0 ? new Object() : world,
                    kind == 1 ? 21 : 20,
                    kind == 2 ? 31 : 30,
                    kind == 3 ? selected().runtime : sample.runtime));
            assertFalse(gate.consume(world, 20, 30, sample.runtime));
        }
    }

    @Test
    public void profileSwitchOrRetireBetweenPhasesRejectsPendingWork() {
        var sample = selected();
        var gate = new MaidAfterNavigationGate();
        var world = new Object();
        assertTrue(gate.book(world, 1, 1, sample.runtime));
        sample.runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE);
        assertFalse(gate.consume(world, 1, 1, sample.runtime));
        assertFalse(gate.book(world, 2, 2, sample.runtime));
        sample.runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT);
        assertTrue(gate.book(world, 3, 3, sample.runtime));
        sample.runtime.retire(InvalidationReason.UNLOADED);
        assertFalse(gate.consume(world, 3, 3, sample.runtime));
    }

    private static Sample selected() {
        var sample = new Sample(true);
        assertEquals(SelectionResult.SELECTED, sample.runtime.select(MaidBrainProfile.FOLLOW_CORE_COMPONENT));
        sample.events.clear();
        return sample;
    }

    private static final class Sample {

        final List<String> events = new ArrayList<>();
        final MaidPolicyRuntime runtime;
        Runnable onTick = () -> {};
        Runnable onAfter = () -> {};
        Runnable onCancel = () -> {};
        boolean failLegacyCancel, failComponentCancel;

        Sample(boolean component) {
            runtime = new MaidPolicyRuntime(UUID.randomUUID(), new MaidPolicyRuntime.LegacyActions() {

                public boolean tickCombat() {
                    events.add("combat");
                    return false;
                }

                public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
                    events.add("life");
                    return false;
                }

                public void tickFollow() {
                    events.add("follow");
                }

                public void cancelAll() {
                    events.add("legacy_cancel");
                    onCancel.run();
                    if (failLegacyCancel) throw new IllegalStateException("legacy failure");
                }
            }, !component ? null : new MaidPolicyRuntime.AfterNavigationActions() {

                public void tick(MaidPolicyContext.Token token) {
                    assertTrue(
                        runtime.getContext()
                            .isCurrent(token));
                    events.add("component");
                    onTick.run();
                }

                public void cancel() {
                    events.add("component_cancel");
                    onCancel.run();
                    if (failComponentCancel) throw new IllegalStateException("component failure");
                }

                public void afterLivingUpdate(MaidPolicyContext.Token token) {
                    assertTrue(
                        runtime.getContext()
                            .isCurrent(token));
                    events.add("after_living");
                    onAfter.run();
                }
            });
        }
    }
}
