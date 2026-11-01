/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/navigation/MaidUnderWaterNodeEvaluator.java, MIT
 * Adaptation: target-independent modern evaluator and query-local visited positions
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext.floor;

import com.github.tartaricacid.touhoulittlemaid.util.CenterOffsetBlockPosSet;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidAmphibiousEvaluator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;

public final class MaidUnderWaterNodeEvaluator extends MaidAmphibiousEvaluator implements ICachedEvaluator {

    private CenterOffsetBlockPosSet visited;

    public MaidUnderWaterNodeEvaluator(MaidPathContext actor) {
        super(actor);
        canPassDoors = true;
    }

    @Override
    public MaidPathNode start() {
        return startNode(floor(actor.x - actor.width / 2), floor(actor.y + .5), floor(actor.z - actor.width / 2));
    }

    @Override
    public void init(int x, int y, int z, int cx, int cy, int cz) {
        visited = new CenterOffsetBlockPosSet(x, y, z, cx, cy, cz);
    }

    @Override
    public void markVis(Point pos) {
        visited.markVis(pos);
    }

    @Override
    public void done() {
        visited = null;
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
        if (next != null && visited != null && visited.isVis(next.x, next.y, next.z)) return false;
        if (next != null && region.water(from.x, from.y, from.z)
            && region.fluidEmpty(next.x, next.y, next.z)
            && from.y != next.y
            && (from.x != next.x || from.z != next.z)) return false;
        return super.valid(next, from);
    }

    @Override
    protected MaidPathNode accepted(int x, int y, int z, int step, double floor, int dx, int dz, MaidPathType from) {
        return visited != null && visited.isVis(x, y, z) ? null : super.accepted(x, y, z, step, floor, dx, dz, from);
    }
}
