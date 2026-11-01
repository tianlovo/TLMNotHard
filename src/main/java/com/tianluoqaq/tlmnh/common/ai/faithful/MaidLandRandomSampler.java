package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Vec3;

/** 固定现代LandRandomPos.getPosAway；先稳定点/限制区检查，再向上脱离实体方块、拒水/有成本点 */
public final class MaidLandRandomSampler {

    public interface Actor extends FollowFallbackSampler.Actor {

        boolean solid(BlockPos pos);

        boolean water(BlockPos pos);
    }

    private MaidLandRandomSampler() {}

    public static Vec3 getPosAway(Actor actor, int radius, int vertical, Vec3 avoid) {
        var origin = actor.position();
        return sample(
            actor,
            radius,
            () -> FollowFallbackSampler
                .direction(actor, radius, vertical, origin.x - avoid.x, origin.z - avoid.z, 1.5707963705062866D));
    }

    public static Vec3 getPos(Actor actor, int radius, int vertical) {
        return sample(
            actor,
            radius,
            () -> new BlockPos(
                actor.nextEntityInt(2 * radius + 1) - radius,
                actor.nextEntityInt(2 * vertical + 1) - vertical,
                actor.nextEntityInt(2 * radius + 1) - radius));
    }

    private static Vec3 sample(Actor actor, int radius, java.util.function.Supplier<BlockPos> direction) {
        boolean restricted = FollowFallbackSampler.mobRestricted(actor, radius);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int attempt = 0; attempt < 10; attempt++) {
            var offset = direction.get();
            if (offset == null) continue;
            var pos = FollowFallbackSampler.absolutePosition(actor, radius, offset);
            if (pos.y < actor.minBuildHeight() || pos.y > actor.maxBuildHeight()
                || restricted && !actor.isWithinRestriction(pos)
                || !actor.isStableDestination(pos)) continue;
            if (actor.solid(pos)) {
                pos = new BlockPos(pos.x, pos.y + 1, pos.z);
                while (pos.y < actor.maxBuildHeight() && actor.solid(pos)) pos = new BlockPos(pos.x, pos.y + 1, pos.z);
            }
            if (actor.water(pos) || actor.pathfindingMalus(pos) != 0) continue;
            double score = actor.walkTargetValue(pos);
            if (score > bestScore) {
                best = pos;
                bestScore = score;
            }
        }
        return best == null ? null : new Vec3(best.x + .5, best.y, best.z + .5);
    }
}
