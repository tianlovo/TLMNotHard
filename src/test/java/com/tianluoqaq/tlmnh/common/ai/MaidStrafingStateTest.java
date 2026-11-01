package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class MaidStrafingStateTest {

    @Test
    public void closeTargetBacksAwayAndOwnerBoundarySuppressesBothAxes() {
        var state = new MaidStrafingState();
        state.tick(2, 16, false, () -> 1);
        assertEquals(-.5F, state.forward(), 0);
        assertEquals(-.5F, state.strafe(), 0);
        state.tick(2, 16, true, () -> 1);
        assertEquals(0, state.forward(), 0);
        assertEquals(0, state.strafe(), 0);
        state.tick(8, 16, false, () -> 1);
        assertEquals(.5F, state.forward(), 0);
    }

    @Test
    public void randomPhaseConsumesTwoSamplesOnlyAfterTwentyTicksAndStopRetainsPhase() {
        var state = new MaidStrafingState();
        var samples = new AtomicInteger();
        for (int i = 0; i < 20; i++) state.tick(5, 16, false, () -> {
            samples.incrementAndGet();
            return 0;
        });
        assertEquals(0, samples.get());
        state.stop();
        assertEquals(0, state.strafe(), 0);
        state.tick(5, 16, false, () -> {
            samples.incrementAndGet();
            return 0;
        });
        assertEquals(2, samples.get());
        assertEquals(.5F, state.strafe(), 0);
        assertEquals(-.5F, state.forward(), 0);
        state.tick(16, 16, false, () -> 0);
        assertEquals(0, state.forward(), 0);
    }
}
