package com.tianluoqaq.tlmnh.common.ai.navigation;

import static com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class MaidAmphibiousSearchTest {

    @Test
    public void bodyHeightChangesReachabilityAndTemporaryCostsAreRestored() {
        var region = new Cell(true, false);
        var costs = MaidPathType.maidCosts();
        var swimmer = new MaidPathContext(region, 2.5, 101, 3.5, .6F, .6F, .6F, 3, false, costs);
        // 旧原型的16格范围会在现代walkedDistance规则下返回部分路线；固定TLM跟随范围为64
        assertFalse(MaidAmphibiousSearch.find(swimmer, 10, 101, 3, 16, 0, 1024).canReach);
        var path = MaidAmphibiousSearch.find(swimmer, 10, 101, 3, 64, 0, 1024);
        assertTrue(path.canReach);
        assertTrue(
            path.nodes.stream()
                .anyMatch(n -> n.x == 6 && n.y == 101));
        assertEquals(0, costs[WATER.ordinal()], 0);
        assertEquals(0, costs[WALKABLE.ordinal()], 0);
        assertEquals(8, costs[WATER_BORDER.ordinal()], 0);
        var standing = new MaidPathContext(region, 2.5, 101, 3.5, .6F, 1.5F, .6F, 3, false, MaidPathType.maidCosts());
        assertFalse(MaidAmphibiousSearch.find(standing, 10, 101, 3, 64, 0, 1024).canReach);
    }

    @Test
    public void sealedTargetAndExhaustedBudgetReturnHonestPartialPaths() {
        var actor = new MaidPathContext(
            new Cell(false, true),
            2.5,
            102,
            3.5,
            .6F,
            .6F,
            .6F,
            3,
            false,
            MaidPathType.maidCosts());
        var result = MaidAmphibiousSearch.find(actor, 10, 104, 3, 16, 0, 1024);
        assertFalse(result.canReach);
        assertTrue(result.nodes.size() > 1);
        assertTrue(
            result.nodes.stream()
                .allMatch(n -> n.x < 6));
        var limited = MaidAmphibiousSearch.find(actor, 10, 104, 3, 16, 0, 1);
        assertFalse(limited.canReach);
        assertEquals(1, limited.nodes.size());
    }

    private static final class Cell implements MaidPathRegion {

        final boolean tunnel, wall;

        Cell(boolean tunnel, boolean wall) {
            this.tunnel = tunnel;
            this.wall = wall;
        }

        boolean inside(int x, int y, int z) {
            return x >= 0 && x <= 12 && y >= 100 && y <= 106 && z >= 0 && z <= 6;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return inside(x, y, z) && (x == 0 || x == 12
                || y == 100
                || y == 106
                || z == 0
                || z == 6
                || tunnel && x >= 5 && x <= 7 && y >= 102
                || wall && x == 6);
        }

        @Override
        public MaidPathType rawType(int x, int y, int z) {
            return solid(x, y, z) ? BLOCKED : inside(x, y, z) ? WATER : OPEN;
        }

        @Override
        public boolean water(int x, int y, int z) {
            return inside(x, y, z) && !solid(x, y, z);
        }

        @Override
        public boolean fluidEmpty(int x, int y, int z) {
            return !water(x, y, z);
        }

        @Override
        public boolean rail(int x, int y, int z) {
            return false;
        }

        @Override
        public double floor(int x, int y, int z) {
            return solid(x, y - 1, z) ? y : y - 1;
        }

        @Override
        public boolean collides(double ax, double ay, double az, double bx, double by, double bz) {
            for (int x = (int) Math.floor(ax); x < Math.ceil(bx); x++)
                for (int y = (int) Math.floor(ay); y < Math.ceil(by); y++)
                    for (int z = (int) Math.floor(az); z < Math.ceil(bz); z++) if (solid(x, y, z)) return true;
            return false;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 256;
        }

        @Override
        public int seaLevel() {
            return 63;
        }
    }
}
