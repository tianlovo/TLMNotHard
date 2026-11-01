package com.tianluoqaq.tlmnh.common.ai.navigation;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public class MaidMultiTargetSearchTest {

    @Test
    public void partialResultUsesEndpointManhattanDistanceRatherThanHeuristicDistance() {
        var actor = new MaidPathContext(new Flat(), 1.5, 101, 1.5, .6F, 1.5F, .6F, 3, true, MaidPathType.maidCosts());
        // 第二目标的Euclidean距离更短，但首目标的Manhattan距离更短；预算1不展开邻点
        var targets = Arrays.asList(new Point(4, 101, 3), new Point(5, 101, 1));
        var result = MaidPathSearch.find(actor, false, targets, 64, 0, 1);
        assertFalse(result.canReach);
        assertEquals(1, result.nodes.size());
        assertEquals(5, result.targetX);
        assertEquals(1, result.targetZ);
        assertNull(MaidPathSearch.find(actor, false, Collections.emptyList(), 64, 0, 1024));
    }

    private static final class Flat implements MaidPathRegion {

        public MaidPathType rawType(int x, int y, int z) {
            return y <= 100 ? MaidPathType.BLOCKED : MaidPathType.OPEN;
        }

        public boolean water(int x, int y, int z) {
            return false;
        }

        public boolean fluidEmpty(int x, int y, int z) {
            return true;
        }

        public double floor(int x, int y, int z) {
            return 101;
        }

        public boolean collides(double ax, double ay, double az, double bx, double by, double bz) {
            return ay < 101;
        }

        public boolean rail(int x, int y, int z) {
            return false;
        }

        public int minY() {
            return 0;
        }

        public int maxY() {
            return 256;
        }

        public int seaLevel() {
            return 63;
        }
    }
}
