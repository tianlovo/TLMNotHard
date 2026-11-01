package com.tianluoqaq.tlmnh.qa;

import net.minecraft.pathfinding.PathEntity;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedNavigationController.Outcome;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedNavigationTests {

    private EnhancedNavigationTests() {}

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
        f.owner.setPosition(f.x + 2.5, f.y + 1, f.z + 4.5);
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
                        .isEating(),
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
