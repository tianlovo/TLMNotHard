package com.tianluoqaq.tlmnh.common.ai.perception;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Kind;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Request;

public class MaidSensingBudgetTest {

    @Test
    public void allActorsAndChannelsGetTurnWithoutFastRequesterExceedingQuota() {
        var budget = new MaidSensingBudget(1);
        var requests = java.util.Arrays.stream(Kind.values())
            .map(MaidSensingBudgetTest::request)
            .toArray(Request[]::new);
        var admitted = new HashSet<Request>();
        for (long tick = 0; tick < 16; tick++) {
            int actual = 0;
            for (var r : requests) if (budget.acquire(r, tick)) {
                actual++;
                admitted.add(r);
            }
            assertTrue(actual <= 1);
            assertTrue(budget.used(tick) <= 1);
        }
        assertEquals(requests.length, admitted.size());
        assertTrue(budget.deferred() > 0);
    }

    @Test
    public void staleScopeCancellationDoesNotEraseNewScopeForSameActorOrRefundUsedCredit() {
        var budget = new MaidSensingBudget(1);
        var old = request(Kind.LIVING);
        var successor = new Request(old.actor, UUID.randomUUID(), Kind.LIVING);
        assertTrue(budget.acquire(old, 0));
        assertFalse(budget.acquire(old, 0));
        assertFalse(budget.acquire(successor, 0));
        budget.cancelScope(old.scope);
        assertTrue(budget.queued(successor));
        assertFalse(budget.acquire(successor, 0));
        assertTrue(budget.acquire(successor, 1));
        assertEquals(1, budget.used(1));
    }

    @Test
    public void abandonedHeadCannotStarveLiveRequesterAndClockRewindDropsFutureQueue() {
        var budget = new MaidSensingBudget(1);
        var head = request(Kind.LIFE);
        var active = request(Kind.AIR);
        assertTrue(budget.acquire(head, 0));
        assertFalse(budget.acquire(head, 0));
        assertFalse(budget.acquire(active, 0));
        assertFalse(budget.acquire(active, 1));
        assertFalse(budget.acquire(active, 2));
        assertTrue(budget.acquire(active, 3));
        assertFalse(budget.acquire(head, 3));
        assertTrue(budget.acquire(active, 1));
        assertFalse(budget.queued(head));
    }

    @Test
    public void equalIdentityRequestsShareOneQueueSlotAndClearDoesNotRefund() {
        var budget = new MaidSensingBudget(1);
        var r = request(Kind.LIVING);
        assertTrue(budget.acquire(r, 0));
        assertFalse(budget.acquire(r, 0));
        assertFalse(budget.acquire(new Request(r.actor, r.scope, r.kind), 0));
        assertEquals(1, budget.queued());
        budget.clear();
        assertEquals(0, budget.queued());
        assertFalse(budget.acquire(r, 0));
        assertTrue(budget.acquire(r, 1));
    }

    private static Request request(Kind kind) {
        return new Request(UUID.randomUUID(), UUID.randomUUID(), kind);
    }
}
