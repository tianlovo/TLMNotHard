package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidTargetMemory;

public class MaidTargetMemoryTest {

    @Test
    public void lastVisibleObservationIsImmutableAndExpiresWithoutHiddenUpdates() {
        var memory = new MaidTargetMemory(40);
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var target = new Object();
        var targetId = UUID.randomUUID();
        memory.beginFrame(world, context, 100);
        assertTrue(memory.observeVisible(targetId, target, 3, 64, 5, 65.5));
        var first = memory.recall(targetId, target);
        assertEquals(100, first.getObservedAt());
        assertEquals(MaidTargetMemory.Source.RC2_ENTITY_SENSES, first.getSource());
        memory.beginFrame(world, context, 140);
        assertSame(first, memory.recall(targetId, target));
        memory.beginFrame(world, context, 141);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 8, 70, 9, 71.5));
        var next = memory.recall(targetId, target);
        assertNotSame(first, next);
        assertEquals(3, first.getX(), 0);
        assertEquals(64, first.getFootY(), 0);
        assertEquals(5, first.getZ(), 0);
        assertEquals(65.5, first.getEyeY(), 0);
        assertEquals(8, next.getX(), 0);
        assertEquals(141, next.getObservedAt());
        memory.forgetTarget();
        assertNull(memory.recall(targetId, target));
    }

    @Test
    public void identityLeaseWorldAndClockBoundariesDiscardOldObservations() {
        var memory = new MaidTargetMemory(40);
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var target = new Object();
        var targetId = UUID.randomUUID();
        memory.beginFrame(world, context, 100);
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        assertNull(memory.recall(targetId, new Object()));
        assertNull(memory.recall(UUID.randomUUID(), target));
        assertTrue(memory.recall(targetId, target) != null);
        context.invalidate();
        assertNull(memory.recall(targetId, target));
        assertFalse(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        context.setActive(true);
        memory.beginFrame(world, context, 101);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        memory.beginFrame(new Object(), context, 102);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        memory.beginFrame(world, new MaidPolicyContext(context.getMaidId()), 102);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        memory.beginFrame(world, context, 101);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        memory.beginFrame(world, context, 100);
        assertNull(memory.recall(targetId, target));
        assertTrue(memory.observeVisible(targetId, target, 1, 2, 3, 4));
        assertFalse(memory.observeVisible(targetId, target, Double.NaN, 2, 3, 4));
        assertNull(memory.recall(targetId, target));
        memory.clear();
        assertFalse(memory.observeVisible(targetId, target, 1, 2, 3, 4));
    }
}
