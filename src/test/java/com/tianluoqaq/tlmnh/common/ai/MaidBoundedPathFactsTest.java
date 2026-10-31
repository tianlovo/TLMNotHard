package com.tianluoqaq.tlmnh.common.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.EndpointRelation;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.PathFacts;
import com.tianluoqaq.tlmnh.common.ai.MaidBoundedPathPlanner.Point;

/** 路径数据与三维终点事实；不模拟原生World查询或宣称导航行为已通过 */
public class MaidBoundedPathFactsTest {

    @Test
    public void endpointComparisonIncludesHeightAndDoesNotDependOnProgressIndex() {
        var target = new Point(8, 160, -4);
        var path = path(new PathPoint(0, 160, 0), new PathPoint(8, 159, -4));
        assertEquals(EndpointRelation.DIFFERS_FROM_NATIVE_TARGET, PathFacts.inspect(path, target).relation);
        path = path(new PathPoint(0, 160, 0), new PathPoint(8, 160, -4));
        path.setCurrentPathIndex(path.getCurrentPathLength());
        assertEquals(EndpointRelation.MATCHES_NATIVE_TARGET, PathFacts.inspect(path, target).relation);
    }

    @Test
    public void nullEmptyAndUnknownTargetRemainDifferentFacts() {
        var target = new Point(8, 160, -4);
        assertEquals(EndpointRelation.NULL_PATH, PathFacts.inspect(null, target).relation);
        assertEquals(EndpointRelation.EMPTY_PATH, PathFacts.inspect(path(), target).relation);
        var facts = PathFacts.inspect(path(new PathPoint(8, 160, -4)), null);
        assertEquals(EndpointRelation.UNKNOWN_TARGET, facts.relation);
        assertEquals(8, facts.endpoint.x);
        assertEquals(160, facts.endpoint.y);
        assertEquals(-4, facts.endpoint.z);
    }

    @Test
    public void endpointSnapshotSurvivesLaterNativePathTruncation() {
        var target = new Point(8, 160, -4);
        var path = path(new PathPoint(0, 160, 0), new PathPoint(8, 160, -4));
        var created = PathFacts.inspect(path, target);
        path.setCurrentPathLength(1);
        var truncated = PathFacts.inspect(path, target);
        assertEquals(EndpointRelation.MATCHES_NATIVE_TARGET, created.relation);
        assertEquals(2, created.length);
        assertEquals(8, created.endpoint.x);
        assertEquals(EndpointRelation.DIFFERS_FROM_NATIVE_TARGET, truncated.relation);
        assertEquals(1, truncated.length);
        assertEquals(0, truncated.endpoint.x);
    }

    @Test
    public void targetReconstructionPreservesNativeWidthAndFloatRounding() {
        var normal = Point.nativeTarget(new Point(-4, 160, 8), 0.6F);
        assertEquals(-4, normal.x);
        assertEquals(160, normal.y);
        assertEquals(8, normal.z);
        var wide = Point.nativeTarget(new Point(-4, 160, 8), 1.2F);
        assertEquals(-5, wide.x);
        assertEquals(7, wide.z);
        var rounded = Point.nativeTarget(new Point(16_777_217, 160, 8), 0.6F);
        assertEquals(16_777_215, rounded.x);
        assertNull(Point.nativeTarget(new Point(0, 160, 0), Float.NaN));
    }

    private static PathEntity path(PathPoint... points) {
        return new PathEntity(points);
    }
}
