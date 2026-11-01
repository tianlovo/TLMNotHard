package com.tianluoqaq.tlmnh.common.ai.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidUnderWaterNodeEvaluator;

/** 单目标现代A*，保留部分路线canReach、1.5启发权重和TLM上岸启发式 */
public final class MaidAmphibiousSearch {

    private MaidAmphibiousSearch() {}

    public static Result find(MaidPathContext actor, int x, int y, int z, float range, int accuracy, int maxVisited) {
        var evaluator = new MaidUnderWaterNodeEvaluator(actor);
        evaluator.prepare();
        try {
            var start = evaluator.start();
            var goal = evaluator.goal(x, y, z);
            boolean boarding = actor.region.fluidEmpty(x, y, z);
            var best = new Best(goal, boarding);
            var open = new MaidPathHeap();
            start.g = 0;
            start.h = best.update(start);
            start.f = start.h;
            open.add(start);
            var neighbors = new MaidPathNode[32];
            int visited = 0;
            boolean reached = false;
            while (!open.empty()) {
                if (++visited >= maxVisited) break;
                var from = open.pop();
                from.closed = true;
                if (from.manhattan(goal) <= accuracy) {
                    reached = true;
                    break;
                }
                if (from.distance(start) >= range) continue;
                int count = evaluator.neighbors(neighbors, from);
                for (int i = 0; i < count; i++) {
                    var next = neighbors[i];
                    float distance = from.distance(next);
                    next.walked = from.walked + distance;
                    float cost = from.g + distance + next.cost;
                    if (next.walked < range && (next.heapIndex < 0 || cost < next.g)) {
                        next.parent = from;
                        next.g = cost;
                        next.h = best.update(next) * 1.5F;
                        if (next.heapIndex >= 0) open.cost(next, next.g + next.h);
                        else {
                            next.f = next.g + next.h;
                            open.add(next);
                        }
                    }
                }
            }
            var path = new ArrayList<MaidPathNode>();
            for (var node = best.node; node != null; node = node.parent) path.add(node);
            Collections.reverse(path);
            return new Result(path, reached, visited, x, y, z);
        } finally {
            evaluator.done();
        }
    }

    private static final class Best {

        final MaidPathNode goal;
        final boolean boarding;
        float distance = Float.MAX_VALUE;
        MaidPathNode node;

        Best(MaidPathNode goal, boolean boarding) {
            this.goal = goal;
            this.boarding = boarding;
        }

        float update(MaidPathNode candidate) {
            float value = candidate.distance(goal);
            if (boarding && candidate.y < goal.y) value += (goal.y - candidate.y) * 100;
            if (value < distance) {
                distance = value;
                node = candidate;
            }
            return value;
        }
    }

    public static final class Result {

        public final List<MaidPathNode> nodes;
        public final boolean canReach;
        public final int visited, targetX, targetY, targetZ;

        Result(List<MaidPathNode> nodes, boolean canReach, int visited, int x, int y, int z) {
            this.nodes = nodes;
            this.canReach = canReach;
            this.visited = visited;
            targetX = x;
            targetY = y;
            targetZ = z;
        }
    }
}
