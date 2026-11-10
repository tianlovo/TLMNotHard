package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class CombatRangePolicyTest {

    private static final UUID MAID = new UUID(0, 0), ONE = new UUID(0, 1), TWO = new UUID(0, 2);

    @Test
    public void targetRangeRequiresCurrentSessionAndBothDistanceBounds() {
        var p = new CombatRangePolicy();
        assertTrue(p.targetAllowed(ONE, 400, 576, 20, 24));
        assertFalse(p.targetAllowed(ONE, 400.01, 100, 20, 24));
        p.select(ONE);
        assertTrue(p.targetAllowed(ONE, 576, 576, 20, 24));
        assertFalse(p.targetAllowed(TWO, 401, 100, 20, 24));
        assertFalse(p.targetAllowed(ONE, 576.01, 100, 20, 24));
        assertFalse(p.targetAllowed(ONE, 400, 576.01, 20, 24));
        p.cancel();
        assertFalse(p.targetAllowed(ONE, 401, 100, 20, 24));
        assertEquals(16, CombatRangePolicy.normalizedAcquire(20, 16));
    }

    @Test
    public void scoutEventAlertAndTimeReversalAreBounded() {
        var p = new CombatRangePolicy();
        p.tick(8, null, null);
        assertEquals(8, p.detection(8, MAID, ONE, 8, 20, 24), 0);
        p.tick(9, ONE, null);
        assertEquals(20, p.detection(9, MAID, ONE, 8, 20, 24), 0);
        p.tick(29, ONE, null);
        assertEquals(8, p.detection(29, MAID, ONE, 8, 20, 24), 0);
        p.seen(30);
        assertEquals(20, p.detection(89, MAID, ONE, 8, 20, 24), 0);
        assertEquals(8, p.detection(90, MAID, ONE, 8, 20, 24), 0);
        p.select(ONE);
        assertEquals(24, p.detection(90, MAID, ONE, 8, 20, 24), 0);
        p.tick(4, null, null);
        assertFalse(p.retained(ONE));
        assertEquals(20, p.detection(4, MAID, TWO, 8, 20, 24), 0);
        assertEquals(24, p.detection(8, MAID, TWO, 48, 20, 24), 0);
    }

    @Test
    public void cancellationRevokesRetentionButKeepsAlertAcrossArbitration() {
        var policy = new CombatRangePolicy();
        policy.tick(9, ONE, null);
        policy.seen(9);
        policy.select(ONE);
        assertTrue(policy.targetAllowed(ONE, 21 * 21, 21 * 21, 20, 24));
        policy.cancel();
        policy.cancel();
        assertFalse(policy.targetAllowed(ONE, 21 * 21, 21 * 21, 20, 24));
        assertEquals(20, policy.detection(10, MAID, ONE, 8, 20, 24), 0);
        policy.select(ONE);
        policy.clear();
        assertFalse(policy.retained(ONE));
        assertFalse(policy.targetAllowed(ONE, 21 * 21, 21 * 21, 20, 24));
    }

    @Test
    public void scoringRewardsObservedDefenseAndStaysNormalized() {
        assertTrue(CombatRangePolicy.score(10, true, false, false) > CombatRangePolicy.score(10, false, false, false));
        assertTrue(CombatRangePolicy.score(10, false, true, false) > CombatRangePolicy.score(10, false, false, false));
        assertEquals(1, CombatRangePolicy.score(0, true, true, true), 0);
    }
}
