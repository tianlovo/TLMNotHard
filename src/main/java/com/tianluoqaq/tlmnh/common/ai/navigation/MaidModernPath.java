package com.tianluoqaq.tlmnh.common.ai.navigation;

import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;

/** 同一个路线对象保留现代类型/可达事实，并供既有动作所有权边界识别 */
public class MaidModernPath extends PathEntity implements MaidNavigationActor.Path {

    private Object cancellationIdentity = this;
    public final MaidNavigationActor.Point requestedTarget;

    public final MaidPathSearch.Result result;
    private final PathPoint[] points;

    public MaidModernPath(MaidPathSearch.Result result) {
        this(result, null);
    }

    public MaidModernPath(MaidPathSearch.Result result, MaidNavigationActor.Point requested) {
        this(result, points(result), requested);
    }

    private MaidModernPath(MaidPathSearch.Result result, PathPoint[] points, MaidNavigationActor.Point requested) {
        super(points);
        requestedTarget = requested == null
            ? new MaidNavigationActor.Point(result.targetX, result.targetY, result.targetZ)
            : requested;
        this.result = result;
        this.points = points;
    }

    private static PathPoint[] points(MaidPathSearch.Result result) {
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
        return other instanceof MaidModernPath modern && result.water == modern.result.water && super.isSamePath(other);
    }

    void continueCancellation(PathEntity previous) {
        cancellationIdentity = previous instanceof MaidModernPath modern ? modern.cancellationIdentity : previous;
    }

    public boolean continues(PathEntity previous) {
        return previous != null && cancellationIdentity
            == (previous instanceof MaidModernPath modern ? modern.cancellationIdentity : previous);
    }

    @Override
    public boolean done() {
        return isFinished();
    }

    @Override
    public int index() {
        return getCurrentPathIndex();
    }

    @Override
    public int size() {
        return getCurrentPathLength();
    }

    @Override
    public MaidNavigationActor.Point node(int index) {
        var p = getPathPointFromIndex(index);
        return new MaidNavigationActor.Point(p.xCoord, p.yCoord, p.zCoord);
    }

    @Override
    public MaidNavigationActor.Point end() {
        return getFinalPathPoint() == null ? null : node(getCurrentPathLength() - 1);
    }

    @Override
    public void advance() {
        incrementPathIndex();
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
