package com.tianluoqaq.tlmnh.common.combat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind;

public class MaidItemUseStateTest {

    @Test
    public void sustainedBowAndShieldCannotUseDifferentHandsAtOnce() {
        var state = new MaidItemUseState(() -> {});
        var bow = state.tryAcquire(new Object(), new Object(), Hand.MAIN, Kind.BOW, () -> true);
        assertNotNull(bow);
        var shieldOwner = new Object();
        var shieldItem = new Object();
        assertNull(state.tryAcquire(shieldOwner, shieldItem, Hand.OFF, Kind.SHIELD, () -> true));
        assertSame(bow, state.peek());
        assertTrue(state.release(bow));
        assertNotNull(state.tryAcquire(shieldOwner, shieldItem, Hand.OFF, Kind.SHIELD, () -> true));
        assertEquals(Kind.SHIELD, state.getSnapshot().kind);
    }

    @Test
    public void staleBowCancellationCannotClearANewerShieldPose() {
        var notifications = new AtomicInteger();
        var state = new MaidItemUseState(notifications::incrementAndGet);
        var bow = state.tryAcquire(new Object(), new Object(), Hand.MAIN, Kind.BOW, () -> true);
        state.release(bow);
        var shield = state.tryAcquire(new Object(), new Object(), Hand.OFF, Kind.SHIELD, () -> true);
        int published = notifications.get();
        assertFalse(state.release(bow));
        assertEquals(published, notifications.get());
        assertTrue(state.isCurrent(shield));
        assertEquals(Hand.OFF, state.getSnapshot().hand);
        assertTrue(shield.getGeneration() > bow.getGeneration());
    }

    @Test
    public void replacingEquipmentInvalidatesOldLeaseBeforeNewUse() {
        var oldItem = new Object();
        var held = new AtomicReference<>(oldItem);
        var state = new MaidItemUseState(() -> {});
        var old = state.tryAcquire(new Object(), oldItem, Hand.OFF, Kind.SHIELD, () -> held.get() == oldItem);
        var replacement = new Object();
        held.set(replacement);
        var next = state.tryAcquire(new Object(), replacement, Hand.OFF, Kind.SHIELD, () -> held.get() == replacement);
        assertNotNull(next);
        assertFalse(state.isCurrent(old));
        assertFalse(state.release(old));
        assertSame(next, state.peek());
    }

    @Test
    public void sameOwnerMayContinueOnlyTheSameEquipmentAndUse() {
        var state = new MaidItemUseState(() -> {});
        var owner = new Object();
        var item = new Object();
        var lease = state.tryAcquire(owner, item, Hand.OFF, Kind.SHIELD, () -> true);
        assertSame(lease, state.tryAcquire(owner, item, Hand.OFF, Kind.SHIELD, () -> true));
        assertNull(state.tryAcquire(owner, new Object(), Hand.OFF, Kind.SHIELD, () -> true));
        assertNull(state.tryAcquire(owner, item, Hand.MAIN, Kind.BOW, () -> true));
        assertSame(lease, state.peek());
    }

    @Test
    public void authorityLostDuringPublicationDoesNotLeaveGhostUse() {
        var permitted = new AtomicBoolean(true);
        var state = new MaidItemUseState(() -> permitted.set(false));
        assertNull(state.tryAcquire(new Object(), new Object(), Hand.OFF, Kind.SHIELD, permitted::get));
        assertNull(state.peek());
        assertFalse(state.getSnapshot().using);
    }

    @Test
    public void observerFailureRollsBackLeaseAndDoesNotPoisonFutureUse() {
        var failOnce = new AtomicBoolean(true);
        var state = new MaidItemUseState(
            () -> { if (failOnce.getAndSet(false)) throw new IllegalStateException("publication failed"); });
        try {
            state.tryAcquire(new Object(), new Object(), Hand.MAIN, Kind.BOW, () -> true);
            fail("Expected publication failure");
        } catch (IllegalStateException expected) {
            assertEquals("publication failed", expected.getMessage());
        }
        assertNull(state.peek());
        assertNotNull(state.tryAcquire(new Object(), new Object(), Hand.OFF, Kind.SHIELD, () -> true));
    }

    @Test
    public void validationFailureReleasesOnlyItsOwnUse() {
        var broken = new AtomicBoolean(false);
        var state = new MaidItemUseState(() -> {});
        var lease = state.tryAcquire(new Object(), new Object(), Hand.MAIN, Kind.BOW, () -> {
            if (broken.get()) throw new IllegalStateException("invalidated source");
            return true;
        });
        broken.set(true);
        try {
            state.reconcile();
            fail("Expected validation failure");
        } catch (IllegalStateException expected) {
            assertEquals("invalidated source", expected.getMessage());
        }
        assertNull(state.peek());
        var next = state.tryAcquire(new Object(), new Object(), Hand.OFF, Kind.SHIELD, () -> true);
        assertFalse(state.release(lease));
        assertTrue(state.isCurrent(next));
    }

    @Test
    public void acquireCallbackCannotRecursivelyStealTheResource() {
        var reference = new AtomicReference<MaidItemUseState>();
        var attempted = new AtomicBoolean(false);
        var state = new MaidItemUseState(() -> {
            if (attempted.compareAndSet(false, true)) assertNull(
                reference.get()
                    .tryAcquire(new Object(), new Object(), Hand.OFF, Kind.SHIELD, () -> true));
        });
        reference.set(state);
        var bow = state.tryAcquire(new Object(), new Object(), Hand.MAIN, Kind.BOW, () -> true);
        assertTrue(attempted.get());
        assertTrue(state.isCurrent(bow));
    }
}
