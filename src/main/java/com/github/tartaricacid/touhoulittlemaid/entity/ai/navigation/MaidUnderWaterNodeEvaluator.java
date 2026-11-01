/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/navigation/MaidUnderWaterNodeEvaluator.java, MIT
 * Adaptation: target-independent modern evaluator and query-local visited positions
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext.floor;

import java.util.HashSet;
import java.util.Set;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidAmphibiousEvaluator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;

public final class MaidUnderWaterNodeEvaluator extends MaidAmphibiousEvaluator {

    private final Set<Long> visited = new HashSet<>();

    public MaidUnderWaterNodeEvaluator(MaidPathContext actor) {
        super(actor);
        canPassDoors = true;
    }

    @Override
    public MaidPathNode start() {
        return startNode(floor(actor.x - actor.width / 2), floor(actor.y + .5), floor(actor.z - actor.width / 2));
    }

    public void markVisited(int x, int y, int z) {
        visited.add(key(x, y, z));
    }

    @Override
    public void done() {
        visited.clear();
        super.done();
    }

    @Override
    public int neighbors(MaidPathNode[] output, MaidPathNode from) {
        int count = super.neighbors(output, from);
        if (region.water(from.x, from.y, from.z) && region.fluidEmpty(from.x, from.y + 1, from.z)) {
            var above = node(from.x, from.y + 1, from.z);
            if (!above.closed) {
                above.cost++;
                output[count++] = above;
            }
        }
        return count;
    }

    @Override
    protected boolean valid(MaidPathNode next, MaidPathNode from) {
        if (next != null && visited.contains(key(next.x, next.y, next.z))) return false;
        if (next != null && region.water(from.x, from.y, from.z)
            && region.fluidEmpty(next.x, next.y, next.z)
            && from.y != next.y
            && (from.x != next.x || from.z != next.z)) return false;
        return super.valid(next, from);
    }

    @Override
    protected MaidPathNode accepted(int x, int y, int z, int step, double floor, int dx, int dz, MaidPathType from) {
        return visited.contains(key(x, y, z)) ? null : super.accepted(x, y, z, step, floor, dx, dz, from);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 67108863) << 38 | ((long) z & 67108863) << 12 | (long) y & 4095;
    }
}
