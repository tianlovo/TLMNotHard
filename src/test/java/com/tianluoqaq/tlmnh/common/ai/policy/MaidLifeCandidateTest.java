package com.tianluoqaq.tlmnh.common.ai.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeCandidate.Kind;

public class MaidLifeCandidateTest {

    @Test
    public void coordinatesDoNotAuthorizeReplacedExpiredOrInvalidatedTargets() {
        var world = new Object();
        var tile = new Object();
        var context = new MaidPolicyContext(UUID.randomUUID());
        var candidate = new MaidLifeCandidate(Kind.BED, 0, 3, 64, 5, 100, world, context, tile);
        assertTrue(candidate.matches(world, context, 120, tile));
        assertFalse(candidate.matches(world, context, 121, tile));
        assertFalse(candidate.matches(world, context, 99, tile));
        assertFalse(candidate.matches(new Object(), context, 100, tile));
        assertFalse(candidate.matches(world, context, 100, new Object()));
        assertFalse(candidate.matches(world, new MaidPolicyContext(context.getMaidId()), 100, tile));
        context.invalidate();
        assertFalse(candidate.matches(world, context, 100, tile));
        context.setActive(true);
        assertFalse(candidate.matches(world, context, 100, tile));
    }
}
