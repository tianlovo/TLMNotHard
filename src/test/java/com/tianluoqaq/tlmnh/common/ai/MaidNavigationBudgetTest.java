package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class MaidNavigationBudgetTest {

    @Test
    public void stableEntityOrderCannotStarveLaterActorsOrExceedWorldQuota() {
        var budget = new MaidNavigationBudget(4);
        int[] admissions = new int[32];
        for (int tick = 0; tick < 32; tick++) {
            int actual = 0;
            for (int actor = 0; actor < admissions.length; actor++) if (budget.acquire(id(actor), tick)) {
                admissions[actor]++;
                actual++;
            }
            assertTrue(actual <= 4);
        }
        for (int count : admissions) assertEquals(4, count);
        assertTrue(budget.deferred() > 0);
    }

    @Test
    public void cancellationReleasesQueuePositionButCannotRefundSpentWork() {
        var budget = new MaidNavigationBudget(1);
        assertTrue(budget.acquire(id(1), 0));
        budget.cancel(id(1));
        assertFalse(budget.acquire(id(2), 0));
        assertFalse(budget.acquire(id(3), 0));
        budget.cancel(id(2));
        assertTrue(budget.acquire(id(3), 1));
        assertEquals(0, budget.queued());
    }

    @Test
    public void abandonedHeadExpiresAndWorldTimeRollbackDiscardsOldQueue() {
        var budget = new MaidNavigationBudget(1);
        assertTrue(budget.acquire(id(0), 10));
        assertFalse(budget.acquire(id(1), 10));
        assertFalse(budget.acquire(id(2), 11));
        assertFalse(budget.acquire(id(2), 12));
        assertTrue(budget.acquire(id(2), 13));
        assertFalse(budget.acquire(id(3), 13));
        assertTrue(budget.acquire(id(4), 1));
        assertEquals(0, budget.queued());
    }

    private static UUID id(int actor) {
        return new UUID(0, actor);
    }
}
