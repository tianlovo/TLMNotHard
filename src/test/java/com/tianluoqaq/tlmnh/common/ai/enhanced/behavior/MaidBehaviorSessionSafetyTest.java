package com.tianluoqaq.tlmnh.common.ai.enhanced.behavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.tianluoqaq.tlmnh.api.behavior.MaidActionAccess;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorGroup;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorInitContext;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorModule;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorOfferSink;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorProvider;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorStage;
import com.tianluoqaq.tlmnh.api.behavior.MaidHandAccess;
import com.tianluoqaq.tlmnh.api.behavior.MaidObservationView;
import com.tianluoqaq.tlmnh.api.behavior.TransitionReason;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidExternalBehaviorSlot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidExternalOfferSink;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidPlatformBehaviorContext;
import com.tianluoqaq.tlmnh.common.ai.enhanced.session.MaidSessionCallbackScope;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentArbiter;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;

public class MaidBehaviorSessionSafetyTest {

    @Test
    public void typedOffersBindExecutionWithoutObjectCastAndRejectRevokedFrames() {
        var policyContext = activePolicyContext();
        var frame = new MaidIntentFrame(policyContext, policyContext.getToken(), 10L);
        var moduleId = MaidBehaviorId.builtin("pickup");
        var executedPayload = new AtomicReference<Integer>();

        var intent = frame
            .offerTyped(moduleId, Group.AUTONOMOUS, Kind.PICKUP, "item-42", .75, 42, executedPayload::set);
        assertTrue(intent.hasBoundAction());
        assertEquals(moduleId, intent.moduleId());
        assertEquals("item-42", intent.rawTarget());
        assertTrue(frame.hasModuleOffer(moduleId));
        assertTrue(frame.hasGroupOffer(Group.AUTONOMOUS));
        assertTrue(frame.hasNonAmbientOffer());

        intent.execute();
        assertEquals(Integer.valueOf(42), executedPayload.get());

        new MaidIntentFrame(policyContext, policyContext.getToken(), 11L);
        try {
            intent.execute();
            fail("Expected revoked frame to reject bound action execution");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void externalOfferSinkRejectsEmergencyAndCommandGroupsAndNamespacesTargets() {
        var policyContext = activePolicyContext();
        var scope = new MaidSessionCallbackScope();
        var idA = MaidBehaviorId.of("mod_a", "assist");
        var idB = MaidBehaviorId.of("mod_b", "assist");
        var sinkA = new MaidExternalOfferSink(idA, scope);
        var sinkB = new MaidExternalOfferSink(idB, scope);
        var frame = new MaidIntentFrame(policyContext, policyContext.getToken(), 1L);

        // EMERGENCY 与 COMMAND 已从公开 API 枚举 MaidBehaviorGroup 中移除，由编译期类型约束替代运行期异常分支
        for (MaidBehaviorGroup exposed : MaidBehaviorGroup.values()) {
            assertFalse("EMERGENCY".equals(exposed.name()));
            assertFalse("COMMAND".equals(exposed.name()));
        }

        try {
            sinkA.offer(MaidBehaviorGroup.AUTONOMOUS, "shared", .5, "x", v -> {});
            fail("Expected unbound/outside-offer sink call to throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }

        long ticketA = scope.enter(MaidSessionCallbackScope.Phase.OFFER, idA, frame::isCurrent);
        sinkA.bindFrame(frame, Runnable::run);
        try {
            sinkA.offer(MaidBehaviorGroup.AUTONOMOUS, "shared", .6, "alpha", v -> {});
        } finally {
            sinkA.clearFrame();
            scope.exit(ticketA);
        }

        long ticketB = scope.enter(MaidSessionCallbackScope.Phase.OFFER, idB, frame::isCurrent);
        sinkB.bindFrame(frame, Runnable::run);
        try {
            sinkB.offer(MaidBehaviorGroup.AUTONOMOUS, "shared", .6, "beta", v -> {});
        } finally {
            sinkB.clearFrame();
            scope.exit(ticketB);
        }

        var arbiter = new MaidIntentArbiter();
        var chosen = arbiter.choose(frame);
        assertNotNull(chosen);
        assertEquals(Kind.EXTENSION, chosen.kind);
        assertEquals(idA, chosen.moduleId());
        assertEquals("shared", chosen.rawTarget());
        assertEquals("mod_a:assist|shared", chosen.target);

        var secondFrame = new MaidIntentFrame(policyContext, policyContext.getToken(), 5L);
        long ticketB2 = scope.enter(MaidSessionCallbackScope.Phase.OFFER, idB, secondFrame::isCurrent);
        sinkB.bindFrame(secondFrame, Runnable::run);
        try {
            sinkB.offer(MaidBehaviorGroup.AUTONOMOUS, "shared", .6, "beta-only", v -> {});
        } finally {
            sinkB.clearFrame();
            scope.exit(ticketB2);
        }
        var secondChosen = arbiter.choose(secondFrame);
        assertEquals(idB, secondChosen.moduleId());
        assertEquals(MaidIntentArbiter.Reason.BEST_AVAILABLE, arbiter.reason());
    }

    @Test
    public void callbackScopeEnforcesObservationAndExecutePhaseBoundariesAndStaleViewInvalidation() {
        var policyContext = activePolicyContext();
        var scope = new MaidSessionCallbackScope();
        var id = MaidBehaviorId.of("mod_guard", "inspector");
        var cachedObservation = new AtomicReference<MaidObservationView>();
        var actionExecuted = new AtomicBoolean();
        var cleanupCount = new AtomicInteger();

        var testContext = stubGatedContext(id, scope);
        var provider = new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return id;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        var obs = context.observation();
                        assertTrue(obs.isCurrent());
                        assertEquals(100L, obs.worldTick());
                        cachedObservation.set(obs);
                        try {
                            context.actions();
                            fail("actions() must be rejected during offer()");
                        } catch (IllegalStateException expected) {
                            // expected
                        }
                        try {
                            context.hands();
                            fail("hands() must be rejected during offer()");
                        } catch (IllegalStateException expected) {
                            // expected
                        }
                        sink.offer(MaidBehaviorGroup.AUTONOMOUS, "node-1", .8, 7, payload -> {
                            assertEquals(Integer.valueOf(7), payload);
                            context.actions()
                                .stopWalk();
                            actionExecuted.set(true);
                        });
                    }

                    @Override
                    public void onDeselected(MaidBehaviorContext context, TransitionReason reason) {
                        assertTrue(
                            context.observation()
                                .isCurrent());
                        try {
                            context.actions();
                            fail("actions() must be rejected during onDeselected()");
                        } catch (IllegalStateException expected) {
                            // expected
                        }
                    }
                };
            }
        };

        var slot = new MaidExternalBehaviorSlot(
            UUID.randomUUID(),
            0,
            provider,
            scope,
            testContext,
            cleanupCount::incrementAndGet);
        var frame = new MaidIntentFrame(policyContext, policyContext.getToken(), 100L);
        slot.offer(frame, frame::isCurrent);

        assertFalse(
            cachedObservation.get()
                .isCurrent());
        try {
            cachedObservation.get()
                .worldTick();
            fail("Cached observation view must throw IllegalStateException outside callback");
        } catch (IllegalStateException expected) {
            // expected
        }

        var arbiter = new MaidIntentArbiter();
        var selected = arbiter.choose(frame);
        selected.execute();
        assertTrue(actionExecuted.get());
        assertTrue(slot.isHealthy());

        slot.onDeselected(TransitionReason.PREEMPTED, () -> true);
        assertTrue(slot.isHealthy());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    public void faultIsolationContainsBrokenExternalModulesAcrossLifecycleWithoutAffectingHealthyPeers() {
        var policyContext = activePolicyContext();
        var scope = new MaidSessionCallbackScope();
        var brokenCreateId = MaidBehaviorId.of("mod_bad", "broken_create");
        var brokenExecId = MaidBehaviorId.of("mod_bad", "broken_exec");
        var healthyId = MaidBehaviorId.of("mod_good", "steady_care");
        var events = new ArrayList<String>();
        var brokenCleanup = new AtomicInteger();

        var brokenCreateSlot = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return brokenCreateId;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                throw new IllegalStateException("create boom");
            }
        }, scope, stubGatedContext(brokenCreateId, scope), () -> {});
        assertTrue(brokenCreateSlot.isFaulted());
        assertFalse(brokenCreateSlot.isHealthy());

        var brokenExecSlot = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return brokenExecId;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return brokenExecId;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        sink.offer(MaidBehaviorGroup.AUTONOMOUS, "boom", .95, "payload", v -> {
                            events.add("broken:execute");
                            throw new RuntimeException("execute boom");
                        });
                    }
                };
            }
        }, scope, stubGatedContext(brokenExecId, scope), brokenCleanup::incrementAndGet);

        var healthySlot = new MaidExternalBehaviorSlot(
            UUID.randomUUID(),
            0,
            recordingProvider(healthyId, MaidBehaviorStage.CARE_AND_AUTONOMOUS, events),
            scope,
            stubGatedContext(healthyId, scope),
            () -> events.add("healthy:cleanup"));

        var arbiter = new MaidIntentArbiter();

        // Tick 1: brokenExec wins (.95 > .70), throws during execute, gets isolated and cleans up resources
        var f1 = new MaidIntentFrame(policyContext, policyContext.getToken(), 1L);
        brokenCreateSlot.offer(f1, f1::isCurrent);
        brokenExecSlot.offer(f1, f1::isCurrent);
        healthySlot.offer(f1, f1::isCurrent);
        var s1 = arbiter.choose(f1);
        assertEquals(brokenExecId, s1.moduleId());
        s1.execute();
        assertTrue(brokenExecSlot.isFaulted());
        assertEquals(1, brokenCleanup.get());
        assertTrue(healthySlot.isHealthy());

        // Tick 2: brokenExec is skipped; healthySlot wins and executes cleanly
        var f2 = new MaidIntentFrame(policyContext, policyContext.getToken(), 2L);
        brokenCreateSlot.offer(f2, f2::isCurrent);
        brokenExecSlot.offer(f2, f2::isCurrent);
        healthySlot.offer(f2, f2::isCurrent);
        var s2 = arbiter.choose(f2);
        assertSame(healthyId, s2.moduleId());
        s2.execute();
        assertTrue(events.contains("healthy:exec:t1"));

        // Tick 3: a module that submits an offer and then throws inside offer() must have its residual offer revoked
        var partialOfferFaultId = MaidBehaviorId.of("mod_bad", "offer_after_submit");
        var partialOfferSlot = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return partialOfferFaultId;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return partialOfferFaultId;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        sink.offer(MaidBehaviorGroup.RECOVERY, "hijack", 1.0, "bad", v -> events.add("SHOULD_NOT_RUN"));
                        throw new RuntimeException("boom after offer");
                    }
                };
            }
        }, scope, stubGatedContext(partialOfferFaultId, scope), () -> {});

        var f3 = new MaidIntentFrame(policyContext, policyContext.getToken(), 3L);
        healthySlot.offer(f3, f3::isCurrent);
        partialOfferSlot.offer(f3, f3::isCurrent);
        assertTrue(partialOfferSlot.isFaulted());
        assertFalse(f3.hasModuleOffer(partialOfferFaultId));
        assertFalse(f3.hasGroupOffer(Group.RECOVERY));
        var s3 = arbiter.choose(f3);
        assertSame(healthyId, s3.moduleId());
    }

    @Test
    public void sessionTransitionSequenceRoutesTargetChangePreemptionAndResetPersistencePreservation() {
        var policyContext = activePolicyContext();
        var scope = new MaidSessionCallbackScope();
        var idA = MaidBehaviorId.of("mod_a", "alpha");
        var idC = MaidBehaviorId.of("mod_c", "bystander");
        var events = new ArrayList<String>();
        var currentTargetForA = new AtomicReference<>("t1");
        var offerAEnabled = new AtomicBoolean(true);

        var slotA = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return idA;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    private int savedSteps;

                    @Override
                    public MaidBehaviorId id() {
                        return idA;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        if (!offerAEnabled.get()) return;
                        String t = currentTargetForA.get();
                        sink.offer(MaidBehaviorGroup.AUTONOMOUS, t, .8, t, payload -> {
                            savedSteps++;
                            events.add("A:exec:" + payload);
                        });
                    }

                    @Override
                    public void onTargetChanged(MaidBehaviorContext context, String prev, String next) {
                        events.add("A:target:" + prev + "->" + next);
                    }

                    @Override
                    public void onDeselected(MaidBehaviorContext context, TransitionReason reason) {
                        events.add("A:deselect:" + reason);
                    }

                    @Override
                    public void cancel() {
                        events.add("A:cancel");
                    }

                    @Override
                    public void writePersistentData(NBTTagCompound tag) {
                        tag.setInteger("Steps", savedSteps);
                    }
                };
            }
        }, scope, stubGatedContext(idA, scope), () -> events.add("A:cleanup"));

        var slotC = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return idC;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.AMBIENT_IDLE;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return idC;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.AMBIENT_IDLE;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {}

                    @Override
                    public void onDeselected(MaidBehaviorContext context, TransitionReason reason) {
                        events.add("C:deselect:" + reason);
                    }

                    @Override
                    public void cancel() {
                        events.add("C:cancel");
                    }
                };
            }
        }, scope, stubGatedContext(idC, scope), () -> {});

        var arbiter = new MaidIntentArbiter();
        MaidBehaviorId activeModule = null;
        Kind activeKind = null;
        String activeTarget = null;
        String activeRawTarget = null;

        // Tick 1: A(t1) starts; C is inactive bystander
        var f1 = new MaidIntentFrame(policyContext, policyContext.getToken(), 1L);
        slotA.offer(f1, f1::isCurrent);
        slotC.offer(f1, f1::isCurrent);
        var s1 = arbiter.choose(f1);
        activeModule = s1.moduleId();
        activeKind = s1.kind;
        activeTarget = s1.target;
        activeRawTarget = s1.rawTarget();
        s1.execute();

        // Tick 2: A(t1) continues without target/deselect callbacks
        var f2 = new MaidIntentFrame(policyContext, policyContext.getToken(), 2L);
        slotA.offer(f2, f2::isCurrent);
        slotC.offer(f2, f2::isCurrent);
        var s2 = arbiter.choose(f2);
        assertTrue(
            Objects.equals(activeModule, s2.moduleId()) && activeKind == s2.kind
                && Objects.equals(activeTarget, s2.target));
        s2.execute();

        // Tick 3: A switches to t2 -> triggers onTargetChanged(t1, t2) on A, never touches C
        currentTargetForA.set("t2");
        var f3 = new MaidIntentFrame(policyContext, policyContext.getToken(), 3L);
        slotA.offer(f3, f3::isCurrent);
        slotC.offer(f3, f3::isCurrent);
        var s3 = arbiter.choose(f3);
        if (Objects.equals(activeModule, s3.moduleId()) && activeKind == s3.kind
            && !Objects.equals(activeTarget, s3.target)) {
            slotA.onTargetChanged(activeRawTarget, s3.rawTarget(), () -> true);
            activeTarget = s3.target;
            activeRawTarget = s3.rawTarget();
        }
        s3.execute();

        // Tick 4: Builtin RECOVERY (HEAL) preempts A(t2) while A still offers -> A gets onDeselected(PREEMPTED), C
        // untouched
        var f4 = new MaidIntentFrame(policyContext, policyContext.getToken(), 4L);
        var healId = MaidBehaviorId.builtin("meal_care");
        f4.offerTyped(healId, Group.RECOVERY, Kind.HEAL, "meal", .9, Boolean.TRUE, v -> events.add("HEAL:exec"));
        slotA.offer(f4, f4::isCurrent);
        slotC.offer(f4, f4::isCurrent);
        var s4 = arbiter.choose(f4);
        assertEquals(healId, s4.moduleId());
        assertTrue(f4.hasModuleOffer(activeModule));
        slotA.onDeselected(TransitionReason.PREEMPTED, () -> true);
        activeModule = s4.moduleId();
        s4.execute();

        // Brain invalidation / RESET: cancel() runs on all slots, yet persistent state of A (3 executions) survives!
        slotA.cancel();
        slotC.cancel();
        var candidateNbt = new NBTTagCompound();
        slotA.writePersistentData(candidateNbt);

        assertEquals(3, candidateNbt.getInteger("Steps"));
        assertFalse(events.contains("C:deselect:PREEMPTED"));
        assertEquals(
            Arrays.asList(
                "A:exec:t1",
                "A:exec:t1",
                "A:target:t1->t2",
                "A:exec:t2",
                "A:deselect:PREEMPTED",
                "A:cleanup",
                "HEAL:exec",
                "A:cancel",
                "A:cleanup",
                "C:cancel"),
            events);
    }

    @Test
    public void deferredHandLeaseCallbacksIsolateModuleFaultWithoutPropagatingThroughReconcile() {
        var policyContext = activePolicyContext();
        var scope = new MaidSessionCallbackScope();
        var useState = new MaidItemUseState(() -> {});
        var heldToken = new Object();
        var backend = new MaidPlatformBehaviorContext.IsolatedHandAccess.Backend() {

            @Override
            public MaidItemUseState.Lease tryAcquireCopyValidated(Object owner, MaidItemUseState.Hand hand,
                MaidItemUseState.Kind kind, Predicate<ItemStack> itemValidator, BooleanSupplier extraGuard) {
                if (!itemValidator.test(null)) return null;
                return useState.tryAcquire(
                    owner,
                    heldToken,
                    hand,
                    kind,
                    () -> itemValidator.test(null) && (extraGuard == null || extraGuard.getAsBoolean()));
            }

            @Override
            public boolean isCurrent(MaidItemUseState.Lease lease) {
                return useState.isCurrent(lease);
            }

            @Override
            public boolean release(MaidItemUseState.Lease lease) {
                return useState.release(lease);
            }
        };

        // Case 1: acquire succeeds during execute; next tick advanceUse -> reconcile throws in itemValidator
        var validatorFaultId = MaidBehaviorId.of("mod_bad", "deferred_validator");
        var throwInValidator = new AtomicBoolean(false);
        var acquiredLeaseRef = new AtomicReference<MaidHandAccess.Lease>();
        var slotRef1 = new MaidExternalBehaviorSlot[1];
        var isolatedHands1 = new MaidPlatformBehaviorContext.IsolatedHandAccess(
            validatorFaultId,
            scope,
            () -> slotRef1[0] != null && slotRef1[0].isHealthy(),
            failure -> { if (slotRef1[0] != null) slotRef1[0].onAsyncCallbackFault("handLeaseKeepAlive", failure); },
            backend,
            new Object());
        var slot1 = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return validatorFaultId;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return validatorFaultId;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        sink.offer(MaidBehaviorGroup.AUTONOMOUS, "use", .8, Boolean.TRUE, ignored -> {
                            var lease = context.hands()
                                .tryAcquireHand(MaidHandAccess.Hand.MAIN, MaidHandAccess.Kind.SNOWBALL, stack -> {
                                    if (throwInValidator.get()) {
                                        throw new IllegalStateException("deferred validator boom");
                                    }
                                    return true;
                                }, () -> true);
                            acquiredLeaseRef.set(lease);
                        });
                    }
                };
            }
        },
            scope,
            stubGatedContextWithHands(validatorFaultId, scope, isolatedHands1),
            isolatedHands1::releaseOwnedResources);
        slotRef1[0] = slot1;

        var arbiter = new MaidIntentArbiter();
        var f1 = new MaidIntentFrame(policyContext, policyContext.getToken(), 10L);
        slot1.offer(f1, f1::isCurrent);
        var s1 = arbiter.choose(f1);
        s1.execute();

        assertNotNull(acquiredLeaseRef.get());
        assertTrue(useState.isCurrent((MaidItemUseState.Lease) acquiredLeaseRef.get()));
        assertTrue(slot1.isHealthy());

        useState.advanceUse(1);
        assertEquals(1, useState.getUsedTicks());

        // Outside any slot callback (EntityMaid.onLivingUpdate -> advanceUse -> reconcile -> isCurrent):
        throwInValidator.set(true);
        useState.advanceUse(1);
        assertNull(useState.peek());
        assertFalse(useState.isCurrent((MaidItemUseState.Lease) acquiredLeaseRef.get()));
        assertTrue(slot1.isFaulted());
        assertFalse(slot1.isHealthy());
        assertEquals(
            "deferred validator boom",
            slot1.lastFault()
                .getMessage());

        // Case 2: acquire succeeds; extraGuard throws AssertionError while a healthy peer is inside EXECUTE
        var guardFaultId = MaidBehaviorId.of("mod_bad", "deferred_guard");
        var healthyPeerId = MaidBehaviorId.of("mod_good", "peer");
        var throwInGuard = new AtomicBoolean(false);
        var slotRef2 = new MaidExternalBehaviorSlot[1];
        var isolatedHands2 = new MaidPlatformBehaviorContext.IsolatedHandAccess(
            guardFaultId,
            scope,
            () -> slotRef2[0] != null && slotRef2[0].isHealthy(),
            failure -> { if (slotRef2[0] != null) slotRef2[0].onAsyncCallbackFault("handLeaseKeepAlive", failure); },
            backend,
            new Object());
        var slot2 = new MaidExternalBehaviorSlot(UUID.randomUUID(), 0, new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return guardFaultId;
            }

            @Override
            public MaidBehaviorStage stage() {
                return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return guardFaultId;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return MaidBehaviorStage.CARE_AND_AUTONOMOUS;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        sink.offer(
                            MaidBehaviorGroup.AUTONOMOUS,
                            "shield",
                            .8,
                            Boolean.TRUE,
                            ignored -> context.hands()
                                .tryAcquireHand(
                                    MaidHandAccess.Hand.OFF,
                                    MaidHandAccess.Kind.SHIELD,
                                    stack -> true,
                                    () -> {
                                        if (throwInGuard.get()) {
                                            throw new AssertionError("deferred guard assertion");
                                        }
                                        return true;
                                    }));
                    }
                };
            }
        },
            scope,
            stubGatedContextWithHands(guardFaultId, scope, isolatedHands2),
            isolatedHands2::releaseOwnedResources);
        slotRef2[0] = slot2;

        var f2 = new MaidIntentFrame(policyContext, policyContext.getToken(), 11L);
        slot2.offer(f2, f2::isCurrent);
        arbiter.choose(f2)
            .execute();
        assertNotNull(useState.peek());
        assertTrue(slot2.isHealthy());

        throwInGuard.set(true);
        long peerTicket = scope.enter(MaidSessionCallbackScope.Phase.EXECUTE, healthyPeerId, () -> true);
        try {
            var peerLease = useState.tryAcquire(
                new Object(),
                new Object(),
                MaidItemUseState.Hand.MAIN,
                MaidItemUseState.Kind.BOW,
                () -> true);
            assertNotNull(peerLease);
            assertTrue(slot2.isFaulted());
            scope.requireExecute(healthyPeerId);
            useState.release(peerLease);
        } finally {
            scope.exit(peerTicket);
        }

        // Case 3: fatal JVM Error (non-Linkage/Assertion) preserves original system error propagation
        var oomModuleId = MaidBehaviorId.of("mod_bad", "jvm_oom");
        var throwOom = new AtomicBoolean(false);
        var isolatedHands3 = new MaidPlatformBehaviorContext.IsolatedHandAccess(
            oomModuleId,
            scope,
            () -> true,
            ignored -> fail("Fatal JVM Error must not be swallowed as module fault"),
            backend,
            new Object());
        long oomTicket = scope.enter(MaidSessionCallbackScope.Phase.EXECUTE, oomModuleId, () -> true);
        try {
            assertNotNull(
                isolatedHands3.tryAcquireHand(MaidHandAccess.Hand.MAIN, MaidHandAccess.Kind.FOOD, stack -> true, () -> {
                    if (throwOom.get()) throw new OutOfMemoryError("simulated jvm oom");
                    return true;
                }));
        } finally {
            scope.exit(oomTicket);
        }
        throwOom.set(true);
        try {
            useState.reconcile();
            fail("Expected fatal OutOfMemoryError to propagate");
        } catch (OutOfMemoryError expected) {
            assertEquals("simulated jvm oom", expected.getMessage());
        }
        assertNull(useState.peek());
    }

    private static MaidPolicyContext activePolicyContext() {
        return new MaidPolicyRuntime(UUID.randomUUID(), new MaidPolicyRuntime.LegacyActions() {

            @Override
            public boolean tickCombat() {
                return false;
            }

            @Override
            public boolean tickLife(MaidPolicyContext context, MaidPolicyContext.Token token, boolean permitted) {
                return false;
            }

            @Override
            public void tickFollow() {}

            @Override
            public void cancelAll() {}
        }).getContext();
    }

    private static MaidBehaviorProvider recordingProvider(MaidBehaviorId id, MaidBehaviorStage stage,
        List<String> events) {
        return new MaidBehaviorProvider() {

            @Override
            public MaidBehaviorId id() {
                return id;
            }

            @Override
            public MaidBehaviorStage stage() {
                return stage;
            }

            @Override
            public MaidBehaviorModule create(MaidBehaviorInitContext initContext) {
                return new MaidBehaviorModule() {

                    @Override
                    public MaidBehaviorId id() {
                        return id;
                    }

                    @Override
                    public MaidBehaviorStage stage() {
                        return stage;
                    }

                    @Override
                    public void offer(MaidBehaviorContext context, MaidBehaviorOfferSink sink) {
                        sink.offer(
                            MaidBehaviorGroup.AUTONOMOUS,
                            "t1",
                            .70,
                            "t1",
                            payload -> events.add("healthy:exec:" + payload));
                    }
                };
            }
        };
    }

    private static MaidBehaviorContext stubGatedContext(MaidBehaviorId moduleId, MaidSessionCallbackScope scope) {
        var hand = new MaidHandAccess() {

            @Override
            public Lease tryAcquireHand(Hand handSide, Kind kind, Predicate<ItemStack> itemValidator,
                BooleanSupplier extraGuard) {
                scope.requireExecute(moduleId);
                return null;
            }

            @Override
            public boolean isCurrent(Lease lease) {
                scope.requireExecute(moduleId);
                return false;
            }

            @Override
            public boolean releaseHand(Lease lease) {
                scope.requireExecute(moduleId);
                return false;
            }
        };
        return stubGatedContextWithHands(moduleId, scope, hand);
    }

    private static MaidBehaviorContext stubGatedContextWithHands(MaidBehaviorId moduleId,
        MaidSessionCallbackScope scope, MaidHandAccess hand) {
        var obs = new MaidObservationView() {

            @Override
            public boolean isCurrent() {
                return scope.isObservationCurrent(moduleId);
            }

            @Override
            public long worldTick() {
                scope.requireObservation(moduleId);
                return 100L;
            }

            @Override
            public int entityTicks() {
                scope.requireObservation(moduleId);
                return 50;
            }

            @Override
            public Vec3d position() {
                scope.requireObservation(moduleId);
                return new Vec3d(0, 64, 0);
            }

            @Override
            public Vec3d velocity() {
                scope.requireObservation(moduleId);
                return new Vec3d(0, 0, 0);
            }

            @Override
            public float health() {
                scope.requireObservation(moduleId);
                return 20F;
            }

            @Override
            public float maxHealth() {
                scope.requireObservation(moduleId);
                return 20F;
            }

            @Override
            public int airSupply() {
                scope.requireObservation(moduleId);
                return 300;
            }

            @Override
            public int hunger() {
                scope.requireObservation(moduleId);
                return 20;
            }

            @Override
            public int favorability() {
                scope.requireObservation(moduleId);
                return 0;
            }

            @Override
            public String taskId() {
                scope.requireObservation(moduleId);
                return "touhou_little_maid:idle";
            }

            @Override
            public ScheduleMode schedule() {
                scope.requireObservation(moduleId);
                return ScheduleMode.DAY;
            }

            @Override
            public ActivityPhase activity() {
                scope.requireObservation(moduleId);
                return ActivityPhase.WORK;
            }

            @Override
            public boolean isSitting() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isRiding() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isLeashed() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isSleeping() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isInWater() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isHurt() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean canBrainMove() {
                scope.requireObservation(moduleId);
                return true;
            }

            @Override
            public boolean isHomeEnabled() {
                scope.requireObservation(moduleId);
                return false;
            }

            @Override
            public boolean isWithinActiveDomain(double x, double y, double z) {
                scope.requireObservation(moduleId);
                return true;
            }

            @Override
            public EntitySnapshot owner() {
                scope.requireObservation(moduleId);
                return null;
            }

            @Override
            public List<EntitySnapshot> visibleEntities() {
                scope.requireObservation(moduleId);
                return Collections.emptyList();
            }

            @Override
            public List<EntitySnapshot> nearbyEntities() {
                scope.requireObservation(moduleId);
                return Collections.emptyList();
            }

            @Override
            public WeaponCapabilitySnapshot weaponCapability() {
                scope.requireObservation(moduleId);
                return new WeaponCapabilitySnapshot(false, false, false, 2.0, 0L);
            }

            @Override
            public List<ProjectileThreatSnapshot> projectileThreats() {
                scope.requireObservation(moduleId);
                return Collections.emptyList();
            }
        };

        var act = new MaidActionAccess() {

            @Override
            public boolean walkToBlock(int x, int y, int z, float speed, int closeEnough) {
                scope.requireExecute(moduleId);
                return true;
            }

            @Override
            public boolean walkToEntity(UUID uniqueId, int entityId, float speed, int closeEnough) {
                scope.requireExecute(moduleId);
                return true;
            }

            @Override
            public void stopWalk() {
                scope.requireExecute(moduleId);
            }

            @Override
            public void lookAt(double x, double y, double z) {
                scope.requireExecute(moduleId);
            }

            @Override
            public boolean lookAtEntity(UUID uniqueId, int entityId) {
                scope.requireExecute(moduleId);
                return true;
            }

            @Override
            public void clearLook() {
                scope.requireExecute(moduleId);
            }

            @Override
            public NavigationOutcome lastNavigationOutcome() {
                scope.requireExecute(moduleId);
                return NavigationOutcome.IDLE;
            }

            @Override
            public boolean selectHotbarOrInventoryWeapon(int maidInventorySlot) {
                scope.requireExecute(moduleId);
                return false;
            }

            @Override
            public boolean requestMeleeAttack(UUID targetUniqueId, int targetEntityId) {
                scope.requireExecute(moduleId);
                return false;
            }

            @Override
            public boolean requestShieldGuard() {
                scope.requireExecute(moduleId);
                return false;
            }

            @Override
            public void stopShieldGuard() {
                scope.requireExecute(moduleId);
            }
        };

        return new MaidBehaviorContext() {

            @Override
            public MaidObservationView observation() {
                scope.requireObservation(moduleId);
                return obs;
            }

            @Override
            public MaidActionAccess actions() {
                scope.requireExecute(moduleId);
                return act;
            }

            @Override
            public MaidHandAccess hands() {
                scope.requireExecute(moduleId);
                return hand;
            }
        };
    }
}
