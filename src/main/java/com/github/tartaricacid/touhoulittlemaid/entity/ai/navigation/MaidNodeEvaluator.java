/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/navigation/MaidNodeEvaluator.java, MIT
 * Adaptation: target-world capabilities and shared modern node contracts
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathContext;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidWalkEvaluator;

public final class MaidNodeEvaluator extends MaidWalkEvaluator {

    public MaidNodeEvaluator(MaidPathContext actor) {
        super(actor);
        canOpenDoors = canPassDoors = canFloat = true;
    }

    @Override
    protected MaidPathType raw(int x, int y, int z) {
        return region.groundRawType(x, y, z);
    }

    @Override
    public int neighbors(MaidPathNode[] output, MaidPathNode from) {
        int count = super.neighbors(output, from);
        for (int dy : new int[] { 1, -1 }) {
            if (region.climbable(from.x, from.y + dy, from.z)) {
                var next = node(from.x, from.y + dy, from.z);
                if (!next.closed) {
                    next.cost = 0;
                    next.type = MaidPathType.WALKABLE;
                    if (count + 1 < output.length) output[count++] = next;
                }
            }
        }
        return count;
    }
}
