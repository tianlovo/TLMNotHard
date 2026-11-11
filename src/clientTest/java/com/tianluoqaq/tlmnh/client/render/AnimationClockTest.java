package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AnimationClockTest {

    @Test
    public void independentClocksStartAtEntityAgeAndKeepFractionalTicks() {
        var first = new AnimationClock();
        var second = new AnimationClock();
        assertEquals(100.25, first.advance(100.25, false), 0);
        assertEquals(100.75, first.advance(100.75, false), 0);
        assertEquals(4, second.advance(4, false), 0);
        assertEquals(100.75, first.advance(100.75, false), 0);
        assertEquals(101, first.advance(101, false), 0);
        assertEquals(4, second.ticks(), 0);
    }

    @Test
    public void pauseAndResumeRebaseWithoutCatchingUpHiddenTime() {
        var clock = new AnimationClock();
        clock.advance(10, false);
        assertEquals(10, clock.advance(20, true), 0);
        assertEquals(10, clock.advance(100, true), 0);
        assertEquals(10, clock.advance(150, false), 0);
        assertEquals(10.5, clock.advance(150.5, false), 0);
        assertEquals(10.5, clock.advance(0, false), 0);
        assertEquals(11, clock.advance(0.5, false), 0);
    }

    @Test
    public void invalidInputDoesNotAlterClockState() {
        var clock = new AnimationClock();
        clock.advance(7, false);
        for (double time : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
            assertThrows(IllegalArgumentException.class, () -> clock.advance(time, false));
        assertEquals(8, clock.advance(8, false), 0);
    }
}
