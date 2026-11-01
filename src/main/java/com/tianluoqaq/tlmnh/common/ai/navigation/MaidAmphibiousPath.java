package com.tianluoqaq.tlmnh.common.ai.navigation;

import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;

/** 同一个路线对象保留现代类型/可达事实，并供既有动作所有权边界识别 */
public final class MaidAmphibiousPath extends PathEntity {

    public final MaidAmphibiousSearch.Result result;
    private final PathPoint[] points;

    public MaidAmphibiousPath(MaidAmphibiousSearch.Result result) {
        this(result, points(result));
    }

    private MaidAmphibiousPath(MaidAmphibiousSearch.Result result, PathPoint[] points) {
        super(points);
        this.result = result;
        this.points = points;
    }

    private static PathPoint[] points(MaidAmphibiousSearch.Result result) {
        var points = new PathPoint[result.nodes.size()];
        for (int i = 0; i < points.length; i++) {
            var node = result.nodes.get(i);
            points[i] = new PathPoint(node.x, node.y, node.z);
        }
        return points;
    }

    public MaidPathType currentType() {
        return result.nodes.get(getCurrentPathIndex()).type;
    }

    public boolean canReach() {
        return result.canReach;
    }

    @Override
    public boolean isSamePath(PathEntity other) {
        return other instanceof MaidAmphibiousPath && super.isSamePath(other);
    }

    void raise(int index, int y) {
        var old = result.nodes.get(index);
        var node = new MaidPathNode(old.x, y, old.z);
        node.type = old.type;
        node.cost = old.cost;
        node.g = old.g;
        node.h = old.h;
        node.f = old.f;
        node.walked = old.walked;
        node.closed = old.closed;
        node.parent = old.parent;
        node.heapIndex = old.heapIndex;
        result.nodes.set(index, node);
        points[index] = new PathPoint(node.x, node.y, node.z);
    }
}
