/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/MaidPathFindingBFS.java, MIT
 * Adaptation: target-independent integer positions and modern evaluator boundary
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Predicate;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.navigation.ICachedEvaluator;
import com.github.tartaricacid.touhoulittlemaid.util.CenterOffsetBlockPosSet;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidWalkEvaluator;

/** 查询局部增量BFS，供原版呼吸和生活目标任务使用；不创建或安装执行路线 */
public final class MaidPathFindingBFS implements AutoCloseable {

    private final MaidPathNode[] tmpNode = new MaidPathNode[20];
    private final CenterOffsetBlockPosSet cachePos;
    private final Queue<MaidPathNode> queueNode = new ArrayDeque<>();
    private final MaidWalkEvaluator nodeEvaluator;
    private final Point centerPos;
    private final double maxDistance;
    private final int verticalSearchRange;
    private boolean isFinished, released;

    public MaidPathFindingBFS(MaidWalkEvaluator nodeEvaluator, Point centerPos, float maxDistance,
        int verticalSearchRange) {
        this.nodeEvaluator = nodeEvaluator;
        this.centerPos = centerPos;
        this.maxDistance = maxDistance;
        this.verticalSearchRange = verticalSearchRange;
        int offset = (int) Math.ceil(maxDistance);
        cachePos = new CenterOffsetBlockPosSet(
            offset + 1,
            verticalSearchRange + 1,
            offset + 1,
            centerPos.x,
            centerPos.y,
            centerPos.z);
        if (nodeEvaluator instanceof ICachedEvaluator cached)
            cached.init(offset, verticalSearchRange, offset, centerPos.x, centerPos.y, centerPos.z);
        nodeEvaluator.prepare();
        var start = nodeEvaluator.start();
        if (start != null) {
            cachePos.markVis(point(start));
            queueNode.add(start);
        }
    }

    private boolean canPathReachInternal(Point pos) {
        return cachePos.isVis(pos) || cachePos.isVis(pos.x, pos.y + 1, pos.z);
    }

    public boolean canPathReach(Point pos) {
        if (canPathReachInternal(pos)) return true;
        if (isFinished) return false;
        while (!canPathReachInternal(pos) && !isFinished) searchStep();
        return canPathReachInternal(pos);
    }

    private Point searchStep() {
        if (isFinished) return null;
        if (queueNode.isEmpty()) {
            isFinished = true;
            return null;
        }
        var node = queueNode.remove();
        int neighbors = nodeEvaluator.neighbors(tmpNode, node);
        for (int i = 0; i < neighbors; i++) {
            var next = tmpNode[i];
            if (cachePos.isVis(next.x, next.y, next.z)) continue;
            int dx = next.x - centerPos.x, dy = next.y - centerPos.y, dz = next.z - centerPos.z;
            if (dy > verticalSearchRange || dy < -verticalSearchRange) continue;
            double distance = dx * dx + dz * dz;
            if (distance > maxDistance * maxDistance) continue;
            var pos = point(next);
            cachePos.markVis(pos);
            if (nodeEvaluator instanceof ICachedEvaluator cached) cached.markVis(pos);
            queueNode.add(next);
        }
        return point(node);
    }

    public Optional<Point> find(Predicate<Point> predicate) {
        while (!isFinished) {
            var pos = searchStep();
            if (pos != null && predicate.test(pos)) return Optional.of(pos);
        }
        return Optional.empty();
    }

    public void finish() {
        isFinished = true;
        if (!released) {
            released = true;
            nodeEvaluator.done();
        }
    }

    @Override
    public void close() {
        finish();
    }

    private static Point point(MaidPathNode node) {
        return new Point(node.x, node.y, node.z);
    }
}
