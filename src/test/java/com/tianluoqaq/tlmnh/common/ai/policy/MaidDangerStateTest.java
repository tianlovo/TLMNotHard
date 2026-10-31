package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerState;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidDangerState.Result;

public class MaidDangerStateTest {

    @Test
    public void partialScanCannotEraseOrExtendAnUnexpiredPositive() {
        var state = new MaidDangerState();
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var target = new Object();
        var id = UUID.randomUUID();
        state.beginFrame(world, context, 100);
        state.recordScan(64, 1, id, target);
        var positive = state.getObservation();
        state.beginFrame(world, context, 110);
        state.recordScan(64, 32, null, null);
        assertSame(positive, state.getObservation());
        assertEquals(Result.VISIBLE_HOSTILE, state.getResult());
        state.beginFrame(world, context, 120);
        state.recordScan(64, 32, null, null);
        assertEquals(
            100,
            state.getObservation()
                .getObservedAt());
        state.beginFrame(world, context, 121);
        assertEquals(Result.UNKNOWN, state.getResult());
        state.recordScan(64, 32, null, null);
        assertNull(state.getObservation());
        state.recordScan(1, 1, id, target);
        state.recordScan(1, 1, null, null);
        assertEquals(Result.CLEAR, state.getResult());
        state.beginFrame(world, context, 142);
        assertEquals(Result.UNKNOWN, state.getResult());
    }

    @Test
    public void boundedScansContinuePastTheFirstBatchAndHandlePopulationChanges() {
        var state = new MaidDangerState();
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        state.beginFrame(world, context, 100);
        assertEquals(0, state.scanStart(33));
        state.recordScan(33, 32, null, null);
        assertEquals(Result.UNKNOWN, state.getResult());
        assertEquals(32, state.scanStart(33));
        var id = UUID.randomUUID();
        state.recordScan(33, 1, id, new Object());
        assertEquals(Result.VISIBLE_HOSTILE, state.getResult());
        assertEquals(0, state.scanStart(33));
        state.recordScan(64, 32, null, null);
        assertEquals(0, state.scanStart(2));
        state.discardTarget();
        assertEquals(Result.UNKNOWN, state.getResult());
        state.recordScan(0, 0, null, null);
        assertEquals(Result.CLEAR, state.getResult());
        assertEquals(0, state.scanStart(0));
        context.invalidate();
        assertNull(state.getObservation());
        context.setActive(true);
        assertTrue(state.beginFrame(world, context, 101));
        assertEquals(Result.UNKNOWN, state.getResult());
        state.recordScan(1, 1, id, new Object());
        assertTrue(state.beginFrame(new Object(), context, 102));
        assertEquals(Result.UNKNOWN, state.getResult());
        state.recordScan(0, 0, null, null);
        assertTrue(state.beginFrame(world, context, 100));
        state.recordScan(0, 0, null, null);
        assertTrue(state.beginFrame(world, context, 99));
        assertEquals(Result.UNKNOWN, state.getResult());
    }
}
