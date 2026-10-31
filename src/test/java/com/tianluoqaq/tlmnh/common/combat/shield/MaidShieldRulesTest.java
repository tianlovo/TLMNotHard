package com.tianluoqaq.tlmnh.common.combat.shield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class MaidShieldRulesTest {

    @Test
    public void blockConeUsesStrictRc2EdgesAndYawWrapping() {
        assertTrue(MaidShieldRules.withinAngle(59.999F, 60));
        assertTrue(MaidShieldRules.withinAngle(-59.999F, 60));
        assertFalse(MaidShieldRules.withinAngle(60, 60));
        assertFalse(MaidShieldRules.withinAngle(-60, 60));
        assertEquals(0, MaidShieldRules.relativeYaw(0, 2, 0, 0, 360, () -> .5), 0);
        assertEquals(-180, MaidShieldRules.relativeYaw(0, -2, 0, 0, 0, () -> .5), 0);
    }

    @Test
    public void coincidentSourceConsumesRc2SeparationSamplesInsteadOfAssumingFront() {
        double[] samples = { .99, .01, .99, .01 };
        var cursor = new AtomicInteger();
        float yaw = MaidShieldRules.relativeYaw(1, 1, 1, 1, 0, () -> samples[cursor.getAndIncrement()]);
        assertEquals(4, cursor.get());
        assertEquals(-45, yaw, .001);
        assertFalse(MaidShieldRules.withinAngle(Float.NaN, 60));
        assertFalse(MaidShieldRules.withinAngle(0, Float.POSITIVE_INFINITY));
    }

    @Test
    public void wearDistinguishesSkippedCallbackFromRoundedZero() {
        assertEquals(-1, MaidShieldRules.requestedWear(1, 1));
        assertEquals(0, MaidShieldRules.requestedWear(.49F, 0));
        assertEquals(1, MaidShieldRules.requestedWear(.5F, 0));
        assertEquals(0, MaidShieldRules.requestedWear(1.49F, 1));
        assertEquals(1, MaidShieldRules.requestedWear(1.5F, 1));
        assertEquals(4, MaidShieldRules.requestedWear(4, 0));
    }

    @Test
    public void passiveOneBoundaryMatchesOriginalRatherThanFiveSecondComment() {
        int remaining = MaidShieldRules.PASSIVE_TICKS;
        for (int i = 0; i < 98; i++) remaining = MaidShieldRules.maintainPassive(remaining, true);
        assertEquals(2, remaining);
        remaining = MaidShieldRules.maintainPassive(remaining, true);
        assertEquals(1, remaining);
        assertEquals(0, MaidShieldRules.maintainPassive(remaining, true));
        assertEquals(1, MaidShieldRules.maintainPassive(100, false));
    }

    @Test
    public void activeBehaviorSurvivesItsDeadlineAndStopsStrictlyAfter() {
        assertFalse(MaidShieldRules.activeTimedOut(60, 60));
        assertTrue(MaidShieldRules.activeTimedOut(61, 60));
    }
}
