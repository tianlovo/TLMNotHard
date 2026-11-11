package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.budget.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion.EnhancedNavigationController.Outcome;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedNavigationTests {

    private EnhancedNavigationTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_navigation_opened_obstacle")
    public static void originalBlockedCorridorReopensBeforeCooldownWithoutPollingNewPaths(GameTestHelper helper) {
        int previousLimit = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = previousLimit;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = new ModernCoreTests.Fixture(helper, false);
        f.maid.setPosition(f.x + 2.5, f.y + 1, f.z + 4.5);
        for (int x = 1; x < 20; x++) for (int y = 1; y <= 3; y++) {
            helper.setBlock(x, y, 2, Blocks.stone);
            helper.setBlock(x, y, 6, Blocks.stone);
        }
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Complete N factory rejected obstacle fixture");
        var core = f.maid.getBrainCoordinator()
            .peekEnhancedCore();
        var budget = MaidWorldNavigationBudget.forWorld(helper.getWorld());
        int[] phase = { 0 }, recoveredAt = { -1 }, openedAt = { -1 };
        long[] previousSearches = { 0 }, blockedSearches = { 0 };
        boolean[] done = { false };
        String[] previousState = { "" };
        helper.onEachTick("complete N original-route obstacle clearance", () -> {
            int age = f.maid.ticksExisted;
            long searches = f.navigation.searchCount();
            String state = phase[0] + ":"
                + core.navigation()
                    .outcome();
            if (!state.equals(previousState[0]) || age % 20 == 0) {
                previousState[0] = state;
                var route = f.navigation.getPath();
                System.out.println(
                    "TLMNH_OPENED_OBSTACLE_STATE age=" + age
                        + " phase="
                        + phase[0]
                        + " outcome="
                        + core.navigation()
                            .outcome()
                        + " searches="
                        + searches
                        + " x="
                        + (f.maid.posX - f.x)
                        + " y="
                        + (f.maid.posY - f.y)
                        + " route="
                        + (route == null ? "none" : route.getCurrentPathIndex() + "/" + route.getCurrentPathLength()));
            }
            helper.assertTrue(
                f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady()
                    && f.maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && f.maid.getBrainCoordinator()
                        .peekEnhancedCore() == core
                    && f.maid.getBrainCoordinator()
                        .getFollowController()
                        .getTicks() == 0,
                "Obstacle fixture lost its complete natural N root");
            helper.assertTrue(
                searches - previousSearches[0] <= 1 && budget.used(
                    helper.getWorld()
                        .getTotalWorldTime())
                    <= 1,
                "Obstacle recovery bypassed actual world query quota");
            previousSearches[0] = searches;
            if (phase[0] == 0) {
                if (f.navigation.getPath() == null) return;
                helper.assertTrue(f.maid.posX < f.x + 7, "Original route did not precede the controlled wall");
                for (int y = 1; y <= 3; y++) for (int z = 3; z <= 5; z++) helper.setBlock(8, y, z, Blocks.stone);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                if (core.navigation()
                    .outcome() != Outcome.RECOVERY_WAIT) return;
                helper.assertTrue(
                    f.navigation.noPath() && core.navigation()
                        .recoveryCount() == 1,
                    "Actual original blocked route did not enter its first recovery");
                recoveredAt[0] = age;
                blockedSearches[0] = searches;
                phase[0] = 2;
            } else if (phase[0] == 2) {
                helper.assertTrue(
                    searches == blockedSearches[0] && core.navigation()
                        .failureCount() == 0
                        && core.actor()
                            .peekWalkTarget() != null,
                    "Unchanged original blockage caused a query flood or discarded its live intent");
                // 原走廊外的方块变化不能成为提前重评的凭据
                if (age - recoveredAt[0] == 2) helper.setBlock(4, 4, 1, Blocks.stone);
                if (age - recoveredAt[0] < 6) return;
                for (int y = 1; y <= 3; y++) for (int z = 3; z <= 5; z++) helper.setBlock(8, y, z, Blocks.air);
                openedAt[0] = age;
                phase[0] = 3;
            } else if (phase[0] == 3) {
                helper.assertTrue(
                    age - openedAt[0] <= 2,
                    "Confirmed original corridor clearance still waited for the old 20tick recovery deadline");
                if (core.navigation()
                    .outcome() != Outcome.RUNNING || f.navigation.noPath()) return;
                helper.assertTrue(
                    age - recoveredAt[0] < 20 && searches == blockedSearches[0] + 1
                        && core.actor()
                            .peekCantReachSince() == null,
                    "Early obstacle retry was unbounded, late or retained stale failure memory");
                phase[0] = 4;
            } else if (phase[0] == 4 && f.maid.posX > f.x + 9 && f.maid.getDistanceSqToEntity(f.owner) <= 6.25) {
                helper.assertTrue(
                    core.navigation()
                        .recoveryCount() == 1
                        && core.navigation()
                            .failureCount() == 0,
                    "Opened original route did not finish through natural movement");
                System.out.println(
                    "TLMNH_OPENED_OBSTACLE_PASS recoveryAge=" + recoveredAt[0]
                        + " openedAge="
                        + openedAt[0]
                        + " arrivalAge="
                        + age
                        + " ownerProfile=shared_controlled_ForgeFakePlayer not_multiplayer=true quota=1");
                done[0] = true;
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "enhanced_navigation_air_preflight")
    public static void lowAirPreflightRetainsQueueWithoutAWalkTargetOrFalseFailure(GameTestHelper helper) {
        int previousLimit = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = previousLimit;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = new ModernCoreTests.Fixture(helper, true);
        f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Actual enhanced air actor selection failed");
        var core = f.maid.getBrainCoordinator()
            .peekEnhancedCore();
        var budget = MaidWorldNavigationBudget.forWorld(helper.getWorld());
        f.maid.setAir(60);
        long[] previousSearches = { 0 };
        helper.succeedWhen(() -> {
            long searched = f.navigation.searchCount();
            helper.assertTrue(
                searched - previousSearches[0] <= 1 && budget.used(
                    helper.getWorld()
                        .getTotalWorldTime())
                    <= 1,
                "Breathing preflight bypassed actual A* quota");
            previousSearches[0] = searched;
            if (f.maid.ticksExisted < 5) return false;
            helper.assertTrue(
                core.selectedKind() == com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind.BREATHE
                    && core.navigation()
                        .outcome() == Outcome.IDLE
                    && core.actor()
                        .peekWalkTarget() == null
                    && f.navigation.noPath(),
                "Sealed low-air scene unexpectedly acquired an air route");
            helper.assertTrue(
                searched > 0 && f.navigation.budgetDeferrals() > 0 && budget.queued() == 1,
                "No-path movement erased the actual breathing preflight queue");
            helper.assertTrue(
                core.navigation()
                    .failureCount() == 0
                    && core.actor()
                        .peekCantReachSince() == null
                    && !f.maid.getMealController()
                        .isEating()
                    && core.follow()
                        .attempts() == 0,
                "Budget deferral became failure or began unrelated item use");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "enhanced_navigation_budget")
    public static void threeNaturalActorsShareQuotaPreserveWaitingRoutesAndCancelQueuedIdentity(GameTestHelper helper) {
        int previousLimit = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = previousLimit;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = new ModernCoreTests.Fixture(helper, false);
        var maids = new EntityMaid[] { f.maid, new EntityMaid(helper.getWorld()), new EntityMaid(helper.getWorld()) };
        var cores = new EnhancedCoreBrain[3];
        var navigators = new MaidPathNavigator[3];
        var oldRoutes = new PathEntity[3];
        long[] searched = new long[3], cancelledSearches = { 0 };
        MaidPolicyContext.Token[] retired = { null };
        for (int i = 0; i < maids.length; i++) {
            var maid = maids[i];
            if (i > 0) {
                helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Additional actor owner binding failed");
                maid.setPickup(false);
                helper.spawnEntity(maid, 1.5, 1, i == 1 ? 2.5 : 6.5);
                helper.afterTest(() -> {
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    maid.setDead();
                    helper.getWorld()
                        .removePlayerEntityDangerously(maid);
                });
            }
            helper.assertTrue(
                maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                    == MaidPolicyRuntime.SelectionResult.SELECTED,
                "Actual enhanced actor selection failed");
            cores[i] = maid.getBrainCoordinator()
                .peekEnhancedCore();
            navigators[i] = (MaidPathNavigator) maid.getNavigator();
        }
        var budget = MaidWorldNavigationBudget.forWorld(helper.getWorld());
        int[] phase = { 0 }, changedAt = { 0 }, cancelled = { -1 };
        boolean[] sawInitialWait = { false }, sawRouteWait = { false };
        helper.succeedWhen(() -> {
            int age = f.maid.ticksExisted;
            long delta = 0;
            for (int i = 0; i < maids.length; i++) {
                long current = navigators[i].searchCount();
                delta += current - searched[i];
                searched[i] = current;
                helper.assertTrue(
                    maids[i].getBrainCoordinator()
                        .peekEnhancedCore() == cores[i]
                        && maids[i].getBrainCoordinator()
                            .peekFaithfulBrain() == null,
                    "Quota actor changed decision root");
                if (cores[i].navigation()
                    .outcome() == Outcome.WAITING_BUDGET) {
                    if (navigators[i].noPath()) sawInitialWait[0] = true;
                    else sawRouteWait[0] = true;
                    helper.assertTrue(
                        cores[i].navigation()
                            .failureCount() == 0
                            && cores[i].actor()
                                .peekCantReachSince() == null
                            && cores[i].actor()
                                .peekWalkTarget() != null,
                        "Budget wait became unreachable or erased live target");
                }
            }
            helper.assertTrue(
                delta <= 1 && budget.used(
                    helper.getWorld()
                        .getTotalWorldTime())
                    <= 1,
                "Actual enhanced A* calls exceeded world quota");
            if (phase[0] == 0) {
                if (searched[0] < 1 || searched[1] < 1 || searched[2] < 1) return false;
                helper.assertTrue(sawInitialWait[0], "No natural initial budget wait observed");
                for (int i = 0; i < 3; i++) oldRoutes[i] = navigators[i].getPath();
                f.owner.setPosition(f.x + 15.5, f.y + 1, f.z + 4.5);
                changedAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changedAt[0]) return false;
            if (phase[0] == 1) {
                for (int i = 0; i < 3; i++) if (cores[i].navigation()
                    .outcome() == Outcome.WAITING_BUDGET) {
                        helper.assertTrue(
                            navigators[i].getPath() == oldRoutes[i] && cores[i].actor()
                                .peekPathMemory()
                                .getIdentity() == oldRoutes[i],
                            "Waiting replan destroyed the still-owned route or PATH memory");
                        cancelled[0] = i;
                        break;
                    }
                helper.assertTrue(cancelled[0] >= 0 && sawRouteWait[0], "No active-route budget wait observed");
                int queued = budget.queued();
                var selected = maids[cancelled[0]];
                var context = selected.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .getContext();
                retired[0] = context.getToken();
                selected.getBrainCoordinator()
                    .setSuspended(true);
                cancelledSearches[0] = navigators[cancelled[0]].searchCount();
                helper.assertTrue(
                    budget.queued() == queued - 1 && !context.isCurrent(retired[0])
                        && navigators[cancelled[0]].noPath(),
                    "Suspension kept queued identity, old authority or route");
                f.owner.setPosition(f.x + 18.5, f.y + 1, f.z + 4.5);
                changedAt[0] = age;
                phase[0] = 2;
                return false;
            }
            helper.assertTrue(
                navigators[cancelled[0]].searchCount() == cancelledSearches[0],
                "Retired queued request executed after suspension");
            boolean ready = true;
            for (int i = 0; i < 3; i++) if (i != cancelled[0]) {
                var path = navigators[i].getPath();
                ready &= path instanceof MaidModernPath modern && modern.result.targetX == f.x + 18;
            }
            if (!ready || age < changedAt[0] + 3) return false;
            helper.assertTrue(
                budget.queued() == 0 && budget.deferred() > 0,
                "Fair queue retained stale requests after live-goal admission");
            System.out.println(
                "TLMNH_ENHANCED_NAV_BUDGET_PASS ticks=" + age + " actors=3 quota=1 live_target=true cancel=true");
            return true;
        });
    }
}
