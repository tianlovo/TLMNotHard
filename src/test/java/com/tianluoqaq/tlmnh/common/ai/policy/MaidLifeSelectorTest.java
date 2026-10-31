package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;

public class MaidLifeSelectorTest {

    @Test
    public void nearestUsesStableCoordinatesForTiesAndDoesNotInventAnEmptyChoice() {
        var context = new MaidPolicyContext(UUID.randomUUID());
        var world = new Object();
        var low = intent(context, world, -2, 4);
        var high = intent(context, world, 2, 4);
        var far = intent(context, world, 0, 9);
        var first = MaidLifeSelector.nearest(Arrays.asList(far, high, low));
        assertSame(low, first.getIntent());
        assertEquals(MaidLifeSelector.Reason.NEAREST_VALID_POSITION, first.getReason());
        assertSame(
            low,
            MaidLifeSelector.nearest(Arrays.asList(low, high, far))
                .getIntent());
        assertSame(
            high,
            MaidLifeSelector.nearest(Arrays.asList(far, high))
                .getIntent());
        assertNull(MaidLifeSelector.nearest(Collections.emptyList()));
    }

    private static MaidLifeIntent intent(MaidPolicyContext context, Object world, int x, double distance) {
        var candidate = new MaidLifeCandidate(
            MaidLifeCandidate.Kind.SNACK,
            0,
            x,
            64,
            0,
            100,
            world,
            context,
            new Object());
        return new MaidLifeIntent(MaidLifeIntent.Action.STEAL, candidate, distance);
    }
}
