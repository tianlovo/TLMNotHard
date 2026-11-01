package com.tianluoqaq.tlmnh.qa;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Blocks;

import com.github.tartaricacid.touhoulittlemaid.util.CenterOffsetBlockPosSet;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNode;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathType;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NavigationQueryTests {

    private NavigationQueryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "navigation_query_multiple")
    public static void multipleTargetsUseModernCacheAndKeepSingleTargetNormalizationSeparate(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        boolean[] checked = { false };
        helper.onEachTick("real multiple target query boundary", () -> {
            f.check();
            if (checked[0] || f.maid.ticksExisted < 8) return;
            var actor = f.core.getActor();
            var original = f.navigation.getPath();
            wall(helper, 6, true);
            var reachable = new Point(f.x + 4, f.y + 1, f.z + 4);
            var targets = Arrays.asList(new Point(f.x + 12, f.y + 1, f.z + 4), reachable);
            long searches = f.navigation.searchCount();
            var selected = f.navigation.createBrainPath(actor, targets, 0);
            helper.assertTrue(
                selected != null && selected.path.canReach() && selected.path.result.targetX == reachable.x,
                "Multi-target query did not choose reachable target");
            helper.assertTrue(
                f.navigation.searchCount() == searches + 1 && f.navigation.getPath() == original,
                "Multi-target query installed a route or ran multiple A* searches");
            var cached = f.navigation.createBrainPath(actor, targets, 0);
            helper.assertTrue(
                cached.path == original && f.navigation.searchCount() == searches + 1,
                "Collection query lost the original create-only target metadata phase");
            helper.assertTrue(
                f.navigation.createBrainPath(actor, Collections.emptyList(), 0) == null
                    && f.navigation.searchCount() == searches + 1,
                "Empty targets caused a search or result");
            var high = new Point(f.x + 4, f.y + 6, f.z + 4);
            var raw = f.navigation.createBrainPath(actor, Collections.singleton(high), 0);
            helper.assertTrue(
                raw != null && !raw.path.canReach() && raw.path.result.targetY == high.y,
                "Collection target was incorrectly normalized like Ground's single-position overload");
            var single = f.navigation.createBrainPath(actor, high.x, high.y, high.z, 0);
            helper.assertTrue(
                single != null && single.path.canReach() && single.path.result.targetY == f.y + 1,
                "Single-position Ground target normalization changed");
            helper.assertTrue(f.navigation.getPath() == original, "Read-only queries replaced active movement");
            checked[0] = true;
            System.out.println("TLMNH_QUERY99_MULTIPLE_PASS");
        });
        helper.succeedWhen(() -> checked[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "navigation_query_bfs_ground")
    public static void incrementalArrivalMapIsLocalToOneTerrainQuery(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        boolean[] checked = { false };
        helper.onEachTick("incremental BFS versus sealed and changed terrain", () -> {
            f.check();
            if (checked[0] || f.maid.ticksExisted < 8) return;
            var original = f.navigation.getPath();
            long searches = f.navigation.searchCount();
            wall(helper, 6, true);
            var center = new Point(f.x + 1, f.y + 1, f.z + 4);
            var beyond = new Point(f.x + 12, f.y + 1, f.z + 4);
            try (var map = f.navigation.createArrivalMap(center, 16, 4)) {
                helper.assertTrue(
                    map.canPathReach(new Point(f.x + 4, f.y + 1, f.z + 4)),
                    "Arrival map missed reachable ground");
                helper.assertFalse(map.canPathReach(beyond), "Arrival map crossed a sealed wall");
                wall(helper, 6, false);
                helper.assertFalse(map.canPathReach(beyond), "Finished query unexpectedly re-read changed terrain");
            }
            try (var fresh = f.navigation.createArrivalMap(center, 16, 4)) {
                helper
                    .assertTrue(fresh.canPathReach(beyond), "New arrival map retained previous query's blocked cells");
            }
            helper.assertTrue(
                f.navigation.getPath() == original && f.navigation.searchCount() == searches,
                "BFS replaced the installed route or invoked A*");
            checked[0] = true;
            System.out.println("TLMNH_QUERY99_GROUND_BFS_PASS");
        });
        helper.succeedWhen(() -> checked[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "navigation_query_bfs_water")
    public static void waterArrivalMapReleasesVisitedCacheAndTemporaryCosts(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        boolean[] checked = { false };
        helper.onEachTick("water BFS evaluator lifecycle", () -> {
            f.check();
            if (checked[0] || f.maid.ticksExisted < 8 || !f.navigation.usesWaterNavigation()) return;
            var original = f.navigation.getPath();
            var evaluator = f.navigation.evaluator();
            float walkable = f.navigation.pathfindingCost(MaidPathType.WALKABLE);
            float border = f.navigation.pathfindingCost(MaidPathType.WATER_BORDER);
            var center = new Point(
                (int) Math.floor(f.maid.posX),
                (int) Math.floor(f.maid.posY),
                (int) Math.floor(f.maid.posZ));
            try (var map = new com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS(
                evaluator,
                center,
                6,
                4)) {
                var high = map.find(p -> p.y >= center.y + 2);
                helper.assertTrue(high.isPresent(), "Water BFS did not expand vertically");
                helper.assertTrue(map.canPathReach(high.get()), "Water BFS lost a visited candidate");
            }
            helper.assertTrue(
                f.navigation.pathfindingCost(MaidPathType.WALKABLE) == walkable
                    && f.navigation.pathfindingCost(MaidPathType.WATER_BORDER) == border,
                "BFS leaked amphibious temporary costs");
            evaluator.prepare();
            try {
                var start = evaluator.start();
                helper.assertTrue(
                    evaluator.neighbors(new MaidPathNode[32], start) > 0,
                    "Released water evaluator retained visited exclusions");
            } finally {
                evaluator.done();
            }
            var bits = new CenterOffsetBlockPosSet(2, 2, 2, 10, 100, -10);
            bits.markVis(new Point(9, 99, -11));
            helper.assertTrue(bits.isVis(9, 99, -11) && !bits.isVis(10, 100, -10), "Offset cache aliased cells");
            helper.assertTrue(bits.isVis(12, 100, -10), "Original out-of-domain visited guard changed");
            bits.clear();
            helper.assertFalse(bits.isVis(9, 99, -11), "Offset cache retained a mark after clear");
            helper.assertTrue(f.navigation.getPath() == original, "Water BFS changed active movement");
            checked[0] = true;
            System.out.println("TLMNH_QUERY99_WATER_BFS_PASS");
        });
        helper.succeedWhen(() -> checked[0]);
    }

    private static void wall(GameTestHelper helper, int x, boolean solid) {
        for (int y = 1; y < 10; y++)
            for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, solid ? Blocks.glass : Blocks.air);
    }
}
