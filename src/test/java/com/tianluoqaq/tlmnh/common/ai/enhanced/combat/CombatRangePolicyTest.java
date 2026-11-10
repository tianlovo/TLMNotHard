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

    @Test
    public void unproductiveDetectionRequiresBothAttemptCountAndFullInvulnerabilitySpan() {
        var policy = new CombatRangePolicy();
        // 单女仆5tick节拍在20tick无敌帧下交替拒收与命中，不应误判无产出
        for (long tick = 5; tick <= 60; tick += 10) {
            policy.recordMeleeOutcome(ONE, tick, false);
            assertFalse(policy.isMeleeUnproductive(ONE, tick));
            policy.recordMeleeOutcome(ONE, tick + 5, true);
            assertFalse(policy.isMeleeUnproductive(ONE, tick + 5));
        }

        // 高攻速3tick连挥3次跨6tick未满20tick无敌帧周期，不应提前判死
        policy.recordMeleeOutcome(ONE, 100, true);
        policy.recordMeleeOutcome(ONE, 103, false);
        policy.recordMeleeOutcome(ONE, 106, false);
        policy.recordMeleeOutcome(ONE, 109, false);
        assertFalse(policy.isMeleeUnproductive(ONE, 109));
        policy.recordMeleeOutcome(ONE, 112, true);
        assertFalse(policy.isMeleeUnproductive(ONE, 112));

        // 5tick锁相拥挤：t=200..215跨15tick仍保持观察，t=220跨满20tick才触发退让冷却
        policy.recordMeleeOutcome(ONE, 200, false);
        policy.recordMeleeOutcome(ONE, 205, false);
        policy.recordMeleeOutcome(ONE, 210, false);
        policy.recordMeleeOutcome(ONE, 215, false);
        assertFalse(policy.isMeleeUnproductive(ONE, 215));
        policy.recordMeleeOutcome(ONE, 220, false);
        assertTrue(policy.isMeleeUnproductive(ONE, 220));
        assertTrue(policy.isMeleeUnproductive(ONE, 319));
        assertFalse(policy.isMeleeUnproductive(ONE, 320));

        // 超过40tick中断后重算窗口，不把旧零散挥空拼进新交战
        policy.recordMeleeOutcome(TWO, 400, false);
        policy.recordMeleeOutcome(TWO, 405, false);
        policy.recordMeleeOutcome(TWO, 446, false);
        policy.recordMeleeOutcome(TWO, 451, false);
        policy.recordMeleeOutcome(TWO, 456, false);
        policy.recordMeleeOutcome(TWO, 461, false);
        assertFalse(policy.isMeleeUnproductive(TWO, 461));
        policy.recordMeleeOutcome(TWO, 466, false);
        assertTrue(policy.isMeleeUnproductive(TWO, 466));
    }

    @Test
    public void acceptedHitOnlyClearsAttemptedTargetAndCancelPreservesCooldown() {
        var policy = new CombatRangePolicy();
        policy.select(ONE);
        for (long tick = 0; tick <= 20; tick += 5) policy.recordMeleeOutcome(ONE, tick, false);
        assertTrue(policy.isMeleeUnproductive(ONE, 20));

        // 换靶触发cancel只撤销保持半径，不清空原拥挤目标冷却；命中新选目标也不解除原目标冷却
        policy.cancel();
        policy.select(TWO);
        assertFalse(policy.retained(ONE));
        assertTrue(policy.isMeleeUnproductive(ONE, 25));
        policy.recordMeleeOutcome(TWO, 26, true);
        assertFalse(policy.isMeleeUnproductive(TWO, 26));
        assertTrue(policy.isMeleeUnproductive(ONE, 26));
        assertTrue(policy.isMeleeUnproductive(ONE, 119));

        // 原目标自身获得真实命中、显式clear或时钟回退时正常解除
        policy.recordMeleeOutcome(ONE, 50, true);
        assertFalse(policy.isMeleeUnproductive(ONE, 50));
        for (long tick = 60; tick <= 80; tick += 5) policy.recordMeleeOutcome(ONE, tick, false);
        assertTrue(policy.isMeleeUnproductive(ONE, 80));
        policy.tick(10, null, null);
        assertFalse(policy.isMeleeUnproductive(ONE, 10));
        for (long tick = 20; tick <= 40; tick += 5) policy.recordMeleeOutcome(ONE, tick, false);
        assertTrue(policy.isMeleeUnproductive(ONE, 40));
        policy.clear();
        assertFalse(policy.isMeleeUnproductive(ONE, 40));
    }

    @Test
    public void yieldRequiresEqualOrHigherUrgencyAlternativeAndFriendTieBreakRespectsDefense() {
        int normal = CombatRangePolicy.urgency(false, false);
        int maidHurt = CombatRangePolicy.urgency(false, true);
        int ownerHurt = CombatRangePolicy.urgency(true, true);
        assertEquals(0, normal);
        assertEquals(1, maidHurt);
        assertEquals(2, ownerHurt);

        // 单目标绝不退让；高紧迫护主/受击目标不为普通旁观目标退让
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(normal, true, -1));
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(maidHurt, true, -1));
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(ownerHurt, true, -1));
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(normal, false, normal));
        assertTrue(CombatRangePolicy.shouldYieldUnproductive(normal, true, normal));
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(maidHurt, true, normal));
        assertTrue(CombatRangePolicy.shouldYieldUnproductive(maidHurt, true, maidHurt));
        assertTrue(CombatRangePolicy.shouldYieldUnproductive(maidHurt, true, ownerHurt));
        assertFalse(CombatRangePolicy.shouldYieldUnproductive(ownerHurt, true, maidHurt));
        assertTrue(CombatRangePolicy.shouldYieldUnproductive(ownerHurt, true, ownerHurt));

        // 已观测友方贴身目标温和让位给未争抢近邻，但护主与自身受击加分保持完整
        double uncontested = CombatRangePolicy.score(6.1, false, false, false, false, false);
        double crowded = CombatRangePolicy.score(5.3, false, false, false, true, false);
        assertTrue(uncontested > crowded);
        assertEquals(
            CombatRangePolicy.score(5.3, true, false, false),
            CombatRangePolicy.score(5.3, true, false, false, true, false),
            0);
        assertEquals(
            CombatRangePolicy.score(5.3, false, true, false),
            CombatRangePolicy.score(5.3, false, true, false, true, false),
            0);
        assertEquals(0, CombatRangePolicy.score(3, false, false, true, false, true), 0);
        assertEquals(0, CombatRangePolicy.score(100, false, false, false, true, false), 0);
    }
}
