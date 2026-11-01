package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
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

public class MaidPolicyRuntimeTest {

    @Test
    public void survivalPreemptsPanicCombatAndFollowButStillCleansLife() {
        var actor = new TestActions();
        actor.surviving = true;
        actor.panicking = true;
        actor.runtime.tick(() -> { throw new AssertionError("Survival cannot evaluate ordinary life permission"); });
        events(actor, "life:false");
        assertEquals(0, actor.panicCalls);
        actor.events.clear();
        actor.surviving = actor.panicking = false;
        actor.runtime.tick(() -> true);
        events(actor, "combat", "life:true", "follow");
        assertEquals(1, actor.panicCalls);
    }

    @Test
    public void survivalInvalidationCannotContinueWithTheOldToken() {
        var actor = new TestActions();
        actor.onSurvival = () -> actor.runtime.invalidate(InvalidationReason.OWNER_CHANGED);
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
        assertEquals(0, actor.panicCalls);
    }

    @Test
    public void panicRunsLifeCleanupAndSuppressesCombatFollowAndPermission() {
        var actor = new TestActions();
        actor.panicking = true;
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> {
            fail("Panic must suppress ordinary permission");
            return true;
        }));
        events(actor, "life:false");
        actor.events.clear();
        actor.panicking = false;
        actor.runtime.tick(() -> true);
        events(actor, "combat", "life:true", "follow");
    }

    @Test
    public void defaultPreservesCombatLifeFollowAndLazyPermissionOrder() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, runtime.getProfile());
        assertTrue(runtime.isReady());
        assertEquals(TickResult.COMPLETED, runtime.tick(() -> {
            actor.events.add("permission");
            return true;
        }));
        events(actor, "combat", "permission", "life:true", "follow");
        assertSame(runtime.getContext(), actor.lifeContext);
        assertSame(
            runtime.getContext()
                .getToken(),
            actor.lifeToken);
    }

    @Test
    public void combatStillTicksLifeCleanupWithoutEvaluatingPermission() {
        var actor = new TestActions();
        actor.fighting = true;
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> {
            fail("Combat must short-circuit the life permission query");
            return true;
        }));
        events(actor, "combat", "life:false");
    }

    @Test
    public void activeLifeDoesNotAlsoTickFollow() {
        var actor = new TestActions();
        actor.living = true;
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> true));
        events(actor, "combat", "life:true");
    }

    @Test
    public void refusedLifePermissionDoesNotSkipItsCleanupTick() {
        var actor = new TestActions();
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> false));
        events(actor, "combat", "life:false", "follow");
    }

    @Test
    public void unavailableProfilesCancelBeforeSelectionAndNeverFallback() {
        for (var profile : new MaidBrainProfile[] { MaidBrainProfile.FAITHFUL_TLM, MaidBrainProfile.ENHANCED }) {
            var actor = new TestActions();
            var runtime = actor.runtime;
            var previous = runtime.getContext()
                .getToken();
            actor.onCancel = () -> {
                assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, runtime.getProfile());
                assertFalse(
                    runtime.getContext()
                        .isCurrent(previous));
                assertFalse(runtime.isReady());
            };
            assertFalse(profile.isAvailable());
            assertEquals(SelectionResult.UNAVAILABLE, runtime.select(profile));
            assertEquals(profile, runtime.getProfile());
            assertFalse(runtime.isReady());
            assertFalse(
                runtime.getContext()
                    .isCurrent(
                        runtime.getContext()
                            .getToken()));
            assertEquals(TickResult.UNAVAILABLE, runtime.tick(() -> {
                fail("Unavailable policy cannot evaluate or execute legacy actions");
                return true;
            }));
            events(actor, "cancel");
            actor.onCancel = null;
            runtime.invalidate(InvalidationReason.UNLOADED);
            assertEquals(TickResult.UNAVAILABLE, runtime.tick(() -> true));
            events(actor, "cancel", "cancel");
        }
    }

    @Test
    public void explicitRecoveryCancelsBeforeEnablingLegacyAgain() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        runtime.select(MaidBrainProfile.FAITHFUL_TLM);
        actor.onCancel = () -> {
            assertEquals(MaidBrainProfile.FAITHFUL_TLM, runtime.getProfile());
            assertFalse(runtime.isReady());
        };
        assertEquals(SelectionResult.SELECTED, runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
        assertTrue(runtime.isReady());
        assertEquals(TickResult.COMPLETED, runtime.tick(() -> true));
        events(actor, "cancel", "cancel", "combat", "life:true", "follow");
    }

    @Test
    public void unchangedAvailableAndUnavailableSelectionsDoNotCancelAgain() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        var token = runtime.getContext()
            .getToken();
        assertEquals(SelectionResult.UNCHANGED, runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
        assertSame(
            token,
            runtime.getContext()
                .getToken());
        events(actor);
        runtime.select(MaidBrainProfile.ENHANCED);
        token = runtime.getContext()
            .getToken();
        assertEquals(SelectionResult.UNAVAILABLE, runtime.select(MaidBrainProfile.ENHANCED));
        assertSame(
            token,
            runtime.getContext()
                .getToken());
        events(actor, "cancel");
    }

    @Test
    public void everyLifecycleBoundaryInvalidatesOldTokensAndCancelsBeforeResuming() {
        for (var reason : new InvalidationReason[] { InvalidationReason.RESET, InvalidationReason.SUSPENSION_CHANGED,
            InvalidationReason.OWNER_CHANGED, InvalidationReason.WORLD_CHANGED, InvalidationReason.UNLOADED }) {
            var actor = new TestActions();
            var runtime = actor.runtime;
            var context = runtime.getContext();
            var token = context.getToken();
            actor.onCancel = () -> assertFalse(context.isCurrent(token));
            runtime.invalidate(reason);
            assertNotSame(token, context.getToken());
            assertFalse(context.isCurrent(token));
            assertTrue(context.isCurrent(context.getToken()));
            assertEquals(token.getEpoch() + 1, context.getEpoch());
            assertEquals(reason, runtime.getLastInvalidationReason());
            assertEquals(
                context.getMaidId(),
                context.getToken()
                    .getMaidId());
            assertEquals(
                context.getEpoch(),
                context.getToken()
                    .getEpoch());
            assertEquals(TickResult.COMPLETED, runtime.tick(() -> true));
            events(actor, "cancel", "combat", "life:true", "follow");
        }
    }

    @Test
    public void tokensFromAnotherRuntimeCannotAuthorizeTheSameMaidAndEpoch() {
        var maidId = UUID.randomUUID();
        var first = new MaidPolicyRuntime(maidId, new TestActions());
        var second = new MaidPolicyRuntime(maidId, new TestActions());
        var token = first.getContext()
            .getToken();
        assertTrue(
            first.getContext()
                .isCurrent(token));
        assertFalse(
            second.getContext()
                .isCurrent(token));
        assertFalse(
            first.getContext()
                .isCurrent(null));
        assertEquals(
            first.getContext()
                .getEpoch(),
            second.getContext()
                .getEpoch());
    }

    @Test
    public void combatInvalidationDoesNotContinueIntoLifeOrFollow() {
        var actor = new TestActions();
        actor.onCombat = () -> actor.runtime.invalidate(InvalidationReason.OWNER_CHANGED);
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
        actor.onCombat = null;
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel", "combat", "life:true", "follow");
    }

    @Test
    public void permissionInvalidationDoesNotStartLifeUsingAStaleDecision() {
        var actor = new TestActions();
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> {
            actor.events.add("permission");
            actor.runtime.invalidate(InvalidationReason.WORLD_CHANGED);
            return true;
        }));
        events(actor, "combat", "permission", "cancel");
    }

    @Test
    public void lifeInvalidationDoesNotAlsoStartFollow() {
        var actor = new TestActions();
        actor.onLife = () -> actor.runtime.invalidate(InvalidationReason.SUSPENSION_CHANGED);
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> true));
        events(actor, "combat", "life:true", "cancel");
    }

    @Test
    public void followInvalidationIsReportedRatherThanClaimingTheTickCompleted() {
        var actor = new TestActions();
        actor.onFollow = () -> actor.runtime.invalidate(InvalidationReason.UNLOADED);
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> true));
        events(actor, "combat", "life:true", "follow", "cancel");
    }

    @Test
    public void selectionFromAnActionCallbackIsRefusedWithoutAutomaticFallback() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        var token = runtime.getContext()
            .getToken();
        actor.onCombat = () -> assertEquals(
            SelectionResult.REFUSED_REENTRANT,
            runtime.select(MaidBrainProfile.FAITHFUL_TLM));
        assertEquals(TickResult.INVALIDATED, runtime.tick(() -> true));
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, runtime.getProfile());
        assertFalse(
            runtime.getContext()
                .isCurrent(token));
        assertFalse(runtime.isReady());
        assertEquals(TickResult.FAULTED, runtime.tick(() -> true));
        assertEquals(InvalidationReason.REENTRANT_CALL, runtime.getLastInvalidationReason());
        events(actor, "combat", "cancel");
        actor.onCombat = null;
        assertEquals(SelectionResult.SELECTED, runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
        assertEquals(TickResult.COMPLETED, runtime.tick(() -> true));
    }

    @Test
    public void nestedTicksNeverDispatchAnotherPolicyPass() {
        var actor = new TestActions();
        actor.onLife = () -> assertEquals(TickResult.REFUSED_REENTRANT, actor.runtime.tick(() -> {
            fail("Nested tick cannot evaluate permission");
            return true;
        }));
        assertEquals(TickResult.INVALIDATED, actor.runtime.tick(() -> true));
        assertEquals(TickResult.FAULTED, actor.runtime.tick(() -> true));
        events(actor, "combat", "life:true", "cancel");
    }

    @Test
    public void selectionFromCancellationCannotCommitTheOuterSelection() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        actor.onCancel = () -> assertEquals(
            SelectionResult.REFUSED_REENTRANT,
            runtime.select(MaidBrainProfile.ENHANCED));
        assertEquals(SelectionResult.INVALIDATED_DURING_CANCELLATION, runtime.select(MaidBrainProfile.FAITHFUL_TLM));
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, runtime.getProfile());
        assertFalse(runtime.isReady());
        assertEquals(TickResult.FAULTED, runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void lifecycleInvalidationDuringCancellationCannotCommitTheOuterSelection() {
        var actor = new TestActions();
        actor.onCancel = () -> actor.runtime.invalidate(InvalidationReason.UNLOADED);
        assertEquals(SelectionResult.INVALIDATED_DURING_CANCELLATION, actor.runtime.select(MaidBrainProfile.ENHANCED));
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, actor.runtime.getProfile());
        assertEquals(InvalidationReason.UNLOADED, actor.runtime.getLastInvalidationReason());
        assertEquals(TickResult.FAULTED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void nestedTickDuringCancellationCannotRecurseIntoCancelOrDispatch() {
        var actor = new TestActions();
        actor.onCancel = () -> assertEquals(TickResult.REFUSED_REENTRANT, actor.runtime.tick(() -> true));
        assertEquals(
            SelectionResult.INVALIDATED_DURING_CANCELLATION,
            actor.runtime.select(MaidBrainProfile.FAITHFUL_TLM));
        assertFalse(actor.runtime.isReady());
        events(actor, "cancel");
    }

    @Test
    public void callbackFailurePropagatesTheOriginalAndSuppressesCleanupFailure() {
        var actor = new TestActions();
        var original = new IllegalStateException("action failed");
        var cleanup = new IllegalArgumentException("cleanup failed");
        var token = actor.runtime.getContext()
            .getToken();
        actor.onCombat = () -> { throw original; };
        actor.onCancel = () -> { throw cleanup; };
        try {
            actor.runtime.tick(() -> true);
            fail("Action failure must propagate");
        } catch (IllegalStateException actual) {
            assertSame(original, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanup, actual.getSuppressed()[0]);
        }
        assertFalse(
            actor.runtime.getContext()
                .isCurrent(token));
        assertEquals(InvalidationReason.CALLBACK_FAILURE, actor.runtime.getLastInvalidationReason());
        assertEquals(TickResult.FAULTED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
        actor.onCombat = actor.onCancel = null;
        assertEquals(SelectionResult.SELECTED, actor.runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
        assertEquals(TickResult.COMPLETED, actor.runtime.tick(() -> true));
    }

    @Test
    public void failedCancellationCannotInstallOrRunTheRequestedPolicy() {
        var actor = new TestActions();
        var failure = new IllegalStateException("cleanup failed");
        actor.onCancel = () -> { throw failure; };
        try {
            actor.runtime.select(MaidBrainProfile.ENHANCED);
            fail("Cancellation failure must propagate");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, actor.runtime.getProfile());
        assertFalse(actor.runtime.isReady());
        assertEquals(TickResult.FAULTED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void permissionErrorsCancelAndRemainFaultedWithoutSwallowingTheError() {
        var actor = new TestActions();
        var failure = new AssertionError("permission failed");
        try {
            actor.runtime.tick(() -> { throw failure; });
            fail("Permission failure must propagate");
        } catch (AssertionError actual) {
            assertSame(failure, actual);
        }
        assertEquals(TickResult.FAULTED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
    }

    @Test
    public void retiredReferenceCannotResumeWhileAFreshRuntimeCanRunTheSameActor() {
        var actor = new TestActions();
        var old = actor.runtime;
        var oldToken = old.getContext()
            .getToken();
        old.retire(InvalidationReason.IDENTITY_CHANGED);
        long retiredEpoch = old.getContext()
            .getEpoch();
        assertTrue(old.isRetired());
        assertFalse(old.isReady());
        assertFalse(
            old.getContext()
                .isCurrent(oldToken));
        assertFalse(
            old.getContext()
                .isCurrent(
                    old.getContext()
                        .getToken()));
        for (var profile : MaidBrainProfile.values()) assertEquals(SelectionResult.RETIRED, old.select(profile));
        old.invalidate(InvalidationReason.RESET);
        old.retire(InvalidationReason.UNLOADED);
        assertEquals(
            retiredEpoch,
            old.getContext()
                .getEpoch());
        assertEquals(InvalidationReason.IDENTITY_CHANGED, old.getLastInvalidationReason());
        assertEquals(TickResult.RETIRED, old.tick(() -> {
            fail("Retired runtime cannot evaluate permission or drive its actor");
            return true;
        }));
        events(actor, "cancel");
        var replacement = new MaidPolicyRuntime(
            old.getContext()
                .getMaidId(),
            actor);
        assertFalse(replacement.isRetired());
        assertTrue(replacement.isReady());
        assertFalse(
            replacement.getContext()
                .isCurrent(oldToken));
        assertFalse(
            old.getContext()
                .isCurrent(
                    replacement.getContext()
                        .getToken()));
        assertEquals(TickResult.COMPLETED, replacement.tick(() -> true));
        assertSame(replacement.getContext(), actor.lifeContext);
        assertSame(
            replacement.getContext()
                .getToken(),
            actor.lifeToken);
        events(actor, "cancel", "combat", "life:true", "follow");
        assertEquals(TickResult.RETIRED, old.tick(() -> true));
        events(actor, "cancel", "combat", "life:true", "follow");
    }

    @Test
    public void retirementDuringCombatStopsTheRestOfTheOldTick() {
        var actor = new TestActions();
        actor.onCombat = () -> actor.runtime.retire(InvalidationReason.UNLOADED);
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        assertTrue(actor.runtime.isRetired());
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
    }

    @Test
    public void retirementDuringPermissionCannotStartLifeWithTheRetiredContext() {
        var actor = new TestActions();
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> {
            actor.events.add("permission");
            actor.runtime.retire(InvalidationReason.WORLD_CHANGED);
            return true;
        }));
        events(actor, "combat", "permission", "cancel");
    }

    @Test
    public void retirementDuringLifeCannotFallThroughToFollow() {
        var actor = new TestActions();
        actor.onLife = () -> actor.runtime.retire(InvalidationReason.IDENTITY_CHANGED);
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "combat", "life:true", "cancel");
    }

    @Test
    public void retirementDuringSelectionCleanupPreventsCommittingTheNewProfile() {
        var actor = new TestActions();
        actor.onCancel = () -> actor.runtime.retire(InvalidationReason.UNLOADED);
        assertEquals(SelectionResult.RETIRED, actor.runtime.select(MaidBrainProfile.FAITHFUL_TLM));
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, actor.runtime.getProfile());
        assertTrue(actor.runtime.isRetired());
        assertFalse(actor.runtime.isReady());
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void retirementDuringInvalidationCleanupCannotBeReactivatedByTheOuterCall() {
        var actor = new TestActions();
        actor.onCancel = () -> actor.runtime.retire(InvalidationReason.IDENTITY_CHANGED);
        actor.runtime.invalidate(InvalidationReason.RESET);
        assertTrue(actor.runtime.isRetired());
        assertFalse(
            actor.runtime.getContext()
                .isCurrent(
                    actor.runtime.getContext()
                        .getToken()));
        assertFalse(actor.runtime.isReady());
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void retirementLocksOutNestedCallsBeforeInvokingItsOnlyCleanup() {
        var actor = new TestActions();
        var runtime = actor.runtime;
        var oldToken = runtime.getContext()
            .getToken();
        actor.onCancel = () -> {
            assertTrue(runtime.isRetired());
            assertFalse(runtime.isReady());
            assertFalse(
                runtime.getContext()
                    .isCurrent(oldToken));
            long epoch = runtime.getContext()
                .getEpoch();
            assertEquals(TickResult.RETIRED, runtime.tick(() -> {
                fail("Retirement cleanup cannot reenter dispatch");
                return true;
            }));
            assertEquals(SelectionResult.RETIRED, runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
            runtime.invalidate(InvalidationReason.RESET);
            runtime.retire(InvalidationReason.OWNER_CHANGED);
            assertEquals(
                epoch,
                runtime.getContext()
                    .getEpoch());
        };
        runtime.retire(InvalidationReason.UNLOADED);
        assertEquals(InvalidationReason.UNLOADED, runtime.getLastInvalidationReason());
        events(actor, "cancel");
    }

    @Test
    public void failedRetirementCleanupRemainsPermanentWithoutRetryingTheOldActor() {
        var actor = new TestActions();
        var failure = new IllegalStateException("retirement cleanup failed");
        var additional = new IllegalArgumentException("another cleanup failed");
        failure.addSuppressed(additional);
        actor.onCancel = () -> { throw failure; };
        try {
            actor.runtime.retire(InvalidationReason.IDENTITY_CHANGED);
            fail("Retirement cleanup failure must propagate");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(additional, actual.getSuppressed()[0]);
        }
        assertTrue(actor.runtime.isRetired());
        assertFalse(actor.runtime.isReady());
        actor.runtime.invalidate(InvalidationReason.RESET);
        actor.runtime.retire(InvalidationReason.UNLOADED);
        assertEquals(SelectionResult.RETIRED, actor.runtime.select(MaidBrainProfile.LEGACY_PROTOTYPE));
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "cancel");
    }

    @Test
    public void retirementFailureInsideAnActionIsNotCleanedUpTwiceByTheOuterTick() {
        var actor = new TestActions();
        var failure = new IllegalStateException("retirement cleanup failed");
        actor.onCombat = () -> actor.runtime.retire(InvalidationReason.UNLOADED);
        actor.onCancel = () -> { throw failure; };
        try {
            actor.runtime.tick(() -> true);
            fail("Retirement failure must propagate through the action callback");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
        assertTrue(actor.runtime.isRetired());
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
    }

    @Test
    public void callbackFailureKeepsItsIdentityWhenCleanupRetiresAndAlsoFails() {
        var actor = new TestActions();
        var original = new IllegalStateException("action failed");
        var cleanup = new IllegalArgumentException("cleanup failed");
        actor.onCombat = () -> { throw original; };
        actor.onCancel = () -> {
            actor.runtime.retire(InvalidationReason.UNLOADED);
            throw cleanup;
        };
        try {
            actor.runtime.tick(() -> true);
            fail("Action failure must propagate");
        } catch (IllegalStateException actual) {
            assertSame(original, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanup, actual.getSuppressed()[0]);
        }
        assertTrue(actor.runtime.isRetired());
        assertEquals(TickResult.RETIRED, actor.runtime.tick(() -> true));
        events(actor, "combat", "cancel");
    }

    private static void events(TestActions actor, String... expected) {
        assertEquals(Arrays.asList(expected), actor.events);
    }

    private static final class TestActions implements MaidPolicyRuntime.LegacyActions {

        private final List<String> events = new ArrayList<>();
        private final MaidPolicyRuntime runtime = new MaidPolicyRuntime(UUID.randomUUID(), this);
        private boolean fighting, living, panicking, surviving;
        private int panicCalls;
        private MaidPolicyContext lifeContext;
        private MaidPolicyContext.Token lifeToken;
        private Runnable onCombat, onLife, onFollow, onCancel, onSurvival;

        @Override
        public boolean tickSurvival() {
            if (onSurvival != null) onSurvival.run();
            return surviving;
        }

        @Override
        public boolean tickPanic() {
            panicCalls++;
            return panicking;
        }

        @Override
        public boolean tickCombat() {
            events.add("combat");
            if (onCombat != null) onCombat.run();
            return fighting;
        }

        @Override
        public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
            lifeContext = context;
            lifeToken = token;
            assertTrue(context.isCurrent(token));
            events.add("life:" + permitted);
            if (onLife != null) onLife.run();
            return living;
        }

        @Override
        public void tickFollow() {
            events.add("follow");
            if (onFollow != null) onFollow.run();
        }

        @Override
        public void cancelAll() {
            events.add("cancel");
            if (onCancel != null) onCancel.run();
        }
    }
}
