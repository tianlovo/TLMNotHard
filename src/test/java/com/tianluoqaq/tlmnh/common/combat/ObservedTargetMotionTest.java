package com.tianluoqaq.tlmnh.common.combat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ObservedTargetMotionTest {

    @Test
    public void fitsObservedConstantMotionAcrossItsRollingWindow() {
        var history = new ObservedTargetMotion();
        var target = new Object();
        for (int tick = 0; tick < 12; tick++) {
            var velocity = history.observe(target, tick, new BallisticVector(100 + .25 * tick, 70, -40 - .1 * tick));
            assertEquals(tick == 0 ? 0 : .25, velocity.x, 1E-12);
            assertEquals(0, velocity.y, 1E-12);
            assertEquals(tick == 0 ? 0 : -.1, velocity.z, 1E-12);
        }
    }

    @Test
    public void targetSwitchAndUnseenGapNeverReuseVelocity() {
        var history = new ObservedTargetMotion();
        var first = new Object();
        var second = new Object();
        history.observe(first, 0, new BallisticVector(0, 0, 0));
        history.observe(first, 1, new BallisticVector(.5, 0, 0));
        assertEquals(
            0,
            history.observe(second, 2, new BallisticVector(1, 0, 0))
                .length(),
            0);
        history.observe(second, 3, new BallisticVector(1.5, 0, 0));
        assertEquals(
            0,
            history.observe(second, 10, new BallisticVector(4, 0, 0))
                .length(),
            0);
        history.clear();
        assertEquals(
            0,
            history.velocity()
                .length(),
            0);
    }

    @Test
    public void teleportsAndClockRestartsResetInsteadOfProducingExtremeLead() {
        var history = new ObservedTargetMotion();
        var target = new Object();
        history.observe(target, 0, new BallisticVector(0, 0, 0));
        history.observe(target, 1, new BallisticVector(.5, 0, 0));
        assertEquals(
            0,
            history.observe(target, 2, new BallisticVector(100, 0, 0))
                .length(),
            0);
        history.observe(target, 3, new BallisticVector(100.5, 0, 0));
        assertEquals(
            0,
            history.observe(target, 0, new BallisticVector(101, 0, 0))
                .length(),
            0);
    }
}
