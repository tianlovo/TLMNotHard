package com.tianluoqaq.tlmnh.common.ai.navigation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidNodeEvaluator;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.MaidUnderWaterNodeEvaluator;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 现代A*，保留多目标结果选择、部分路线canReach、1.5启发权重和TLM上岸启发式 */
public final class MaidPathSearch {

    private MaidPathSearch() {}

    public static Result find(MaidPathContext actor, boolean water, int x, int y, int z, float range, int accuracy,
        int maxVisited) {
        return find(actor, water, Collections.singleton(new Point(x, y, z)), range, accuracy, maxVisited);
    }

    public static Result find(MaidPathContext actor, boolean water, Collection<Point> targets, float range,
        int accuracy, int maxVisited) {
        if (targets.isEmpty()) return null;
        MaidWalkEvaluator evaluator = water ? new MaidUnderWaterNodeEvaluator(actor) : new MaidNodeEvaluator(actor);
        evaluator.prepare();
        try {
            var start = evaluator.start();
            if (start == null) return null;
            // TLM SelectivePathFinding在任一目标没有流体时，对整个目标集启用上岸启发式
            boolean boarding = water && targets.stream()
                .anyMatch(p -> actor.region.fluidEmpty(p.x, p.y, p.z));
            var unique = new LinkedHashMap<Point, Best>();
            for (var target : targets) unique.computeIfAbsent(
                target,
                ignored -> new Best(evaluator.goal(target.x, target.y, target.z), target, boarding));
            var goals = new ArrayList<>(unique.values());
            var open = new MaidPathHeap();
            start.g = 0;
            start.h = update(goals, start);
            start.f = start.h;
            open.add(start);
            var neighbors = new MaidPathNode[32];
            int visited = 0;
            boolean reached = false;
            while (!open.empty()) {
                if (++visited >= maxVisited) break;
                var from = open.pop();
                from.closed = true;
                for (var goal : goals) {
                    if (from.manhattan(goal.goal) <= accuracy) {
                        goal.reached = true;
                        reached = true;
                    }
                }
                if (reached) break;
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
                        next.h = update(goals, next) * 1.5F;
                        if (next.heapIndex >= 0) open.cost(next, next.g + next.h);
                        else {
                            next.f = next.g + next.h;
                            open.add(next);
                        }
                    }
                }
            }
            Result selected = null;
            float selectedDistance = Float.MAX_VALUE;
            for (var goal : goals) {
                if (reached && !goal.reached) continue;
                var path = new ArrayList<MaidPathNode>();
                for (var node = goal.node; node != null; node = node.parent) path.add(node);
                Collections.reverse(path);
                // Path.getDistToTarget使用末节点到原始目标的Manhattan距离，而非启发式距离
                float distance = path.get(path.size() - 1)
                    .manhattan(new MaidPathNode(goal.target.x, goal.target.y, goal.target.z));
                if (selected == null || !reached && distance < selectedDistance
                    || (reached || distance == selectedDistance) && path.size() < selected.nodes.size()) {
                    selected = new Result(path, reached, visited, goal.target.x, goal.target.y, goal.target.z, water);
                    selectedDistance = distance;
                }
            }
            return selected;
        } finally {
            evaluator.done();
        }
    }

    private static float update(List<Best> goals, MaidPathNode candidate) {
        float distance = Float.MAX_VALUE;
        for (var goal : goals) distance = Math.min(distance, goal.update(candidate));
        return distance;
    }

    private static final class Best {

        final MaidPathNode goal;
        final Point target;
        final boolean boarding;
        float distance = Float.MAX_VALUE;
        MaidPathNode node;
        boolean reached;

        Best(MaidPathNode goal, Point target, boolean boarding) {
            this.goal = goal;
            this.target = target;
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

    public static class Result {

        public final List<MaidPathNode> nodes;
        public final boolean canReach, water;
        public final int visited, targetX, targetY, targetZ;

        Result(List<MaidPathNode> nodes, boolean canReach, int visited, int x, int y, int z, boolean water) {
            this.water = water;
            this.nodes = nodes;
            this.canReach = canReach;
            this.visited = visited;
            targetX = x;
            targetY = y;
            targetZ = z;
        }
    }
}
