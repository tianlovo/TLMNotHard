package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidVisibleLivingEntities;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Kind;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget.Request;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidWorldSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedSensingTests {

    private EnhancedSensingTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 120, batch = "enhanced_sensing_fairness")
    public static void threeNaturalActorsShareOneActualScanAndEveryChannelGetsTurn(GameTestHelper helper) {
        configure(helper);
        var f = fixture(helper, false);
        var maids = new EntityMaid[] { f.maid, new EntityMaid(helper.getWorld()), new EntityMaid(helper.getWorld()) };
        var cores = new EnhancedCoreBrain[3];
        for (int i = 0; i < maids.length; i++) {
            var maid = maids[i];
            if (i > 0) {
                helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Additional sensing actor binding failed");
                maid.setPickup(false);
                maid.setSchedule(MaidSchedule.ALL);
                helper.spawnEntity(maid, 1.5 + i * 2, 1, 4.5);
                helper.afterTest(() -> {
                    maid.getBrainCoordinator()
                        .setSuspended(true);
                    maid.setDead();
                    helper.getWorld()
                        .removePlayerEntityDangerously(maid);
                });
            }
            cores[i] = select(helper, maid);
        }
        var budget = MaidWorldSensingBudget.forWorld(helper.getWorld());
        long[] previous = { 0 };
        helper.succeedWhen(() -> {
            long scanned = 0;
            boolean everyone = true;
            for (int i = 0; i < maids.length; i++) {
                ready(helper, maids[i], cores[i]);
                var p = cores[i].perception();
                scanned += p.livingScanCount() + p.pickupScanCount()
                    + cores[i].life()
                        .scanCount();
                everyone &= p.livingScanCount() > 0 && p.pickupScanCount() > 0
                    && cores[i].life()
                        .scanCount() > 0;
            }
            helper.assertTrue(
                scanned - previous[0] <= 1 && budget.used(
                    helper.getWorld()
                        .getTotalWorldTime())
                    <= 1,
                "Actual entity/pickup/life scans bypassed shared world quota");
            previous[0] = scanned;
            return everyone && budget.deferred() > 0;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_sensing_cache")
    public static void queuedScanPreservesThenExpiresObservationAndSuspensionOnlyCancelsOwnScope(
        GameTestHelper helper) {
        configure(helper);
        var f = fixture(helper, false);
        var pig = new EntityPig(helper.getWorld());
        pig.tasks.taskEntries.clear();
        pig.func_110163_bv();
        helper.spawnEntity(pig, 4.5, 1, 4.5);
        helper.afterTest(pig::setDead);
        var core = select(helper, f.maid);
        var budget = MaidWorldSensingBudget.forWorld(helper.getWorld());
        UUID foreignScope = UUID.randomUUID();
        List<Request> competitors = new ArrayList<>();
        for (int i = 0; i < 64; i++) competitors.add(new Request(UUID.randomUUID(), foreignScope, Kind.LIFE));
        helper.afterTest(() -> budget.cancelScope(foreignScope));
        int[] phase = { 0 }, began = { -1 };
        long[] previousLiving = { -1 };
        MaidVisibleLivingEntities[] old = { null };
        boolean[] kept = { false };
        helper.succeedWhen(() -> {
            ready(helper, f.maid, core);
            int age = f.maid.ticksExisted;
            var p = core.perception();
            if (phase[0] == 0) {
                if (!p.livingObservationFresh() || !p.nearby()
                    .contains(pig)) return false;
                old[0] = p.visible();
                previousLiving[0] = p.livingScanCount();
                began[0] = age;
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                if (age == began[0] + 10) {
                    helper.assertTrue(
                        p.livingObservationFresh() && p.nearby()
                            .contains(pig) && p.livingScanCount() == previousLiving[0] && old[0].contains(pig),
                        "Deferred scan became an empty observation before expiry");
                    kept[0] = true;
                }
                if (age > began[0] + 60 && !p.livingObservationFresh()) {
                    boolean expired = false;
                    try {
                        old[0].contains(pig);
                    } catch (IllegalStateException expected) {
                        expired = true;
                    }
                    helper.assertTrue(
                        kept[0] && p.nearby()
                            .isEmpty()
                            && expired
                            && core.sensing()
                                .queued(Kind.LIVING),
                        "Expired observation or old visible capability survived a queued refresh");
                    f.maid.getBrainCoordinator()
                        .setSuspended(true);
                    for (var kind : Kind.values()) helper.assertFalse(
                        core.sensing()
                            .queued(kind),
                        "Suspension retained this actor's scan request");
                    helper.assertTrue(budget.queued() > 0, "Own cancellation erased foreign scope");
                    budget.cancelScope(foreignScope);
                    f.maid.getBrainCoordinator()
                        .setSuspended(false);
                    phase[0] = 2;
                    return false;
                }
                for (var request : competitors) budget.acquire(
                    request,
                    helper.getWorld()
                        .getTotalWorldTime());
                helper.assertTrue(
                    budget.used(
                        helper.getWorld()
                            .getTotalWorldTime())
                        <= 1,
                    "Synthetic pressure exceeded shared scan credit");
                return false;
            }
            if (!p.livingObservationFresh() || p.livingScanCount() <= previousLiving[0]) return false;
            helper.assertTrue(
                p.nearby()
                    .contains(pig),
                "Resume did not publish a fresh actual query");
            boolean retired = false;
            try {
                old[0].contains(pig);
            } catch (IllegalStateException expected) {
                retired = true;
            }
            helper.assertTrue(retired, "Fresh query revived an old visible capability");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "enhanced_sensing_air")
    public static void airBfsWaitsForSharedCreditThenCreatesRealBreathingRoute(GameTestHelper helper) {
        configure(helper);
        var f = fixture(helper, true);
        for (int x = 5; x <= 7; x++)
            for (int y = 4; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, Blocks.air);
        f.owner.setPosition(f.x + 5.5, f.y + 2, f.z + 4.5);
        var core = select(helper, f.maid);
        var budget = MaidWorldSensingBudget.forWorld(helper.getWorld());
        UUID foreignScope = UUID.randomUUID();
        var one = new Request(UUID.randomUUID(), foreignScope, Kind.AIR);
        var two = new Request(UUID.randomUUID(), foreignScope, Kind.AIR);
        helper.afterTest(() -> budget.cancelScope(foreignScope));
        int[] phase = { 0 }, changed = { -1 };
        long[] searches = { -1 }, airGrants = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f.maid, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (age < 4) return false;
                budget.acquire(
                    one,
                    helper.getWorld()
                        .getTotalWorldTime());
                budget.acquire(
                    two,
                    helper.getWorld()
                        .getTotalWorldTime());
                f.maid.setAir(60);
                searches[0] = f.navigation.searchCount();
                airGrants[0] = budget.admitted(Kind.AIR);
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() == MaidIntentFrame.Kind.BREATHE && core.sensing()
                        .queued(Kind.AIR)
                        && budget.admitted(Kind.AIR) == airGrants[0]
                        && f.navigation.searchCount() == searches[0]
                        && core.actor()
                            .peekWalkTarget() == null,
                    "Deferred air scan fell through to BFS, fallback path or false failure");
                budget.cancelScope(foreignScope);
                phase[0] = 2;
                return false;
            }
            if (age <= 10 || age % 20 == 0) {
                var walk = core.actor()
                    .peekWalkTarget();
                var point = walk == null ? null : walk.currentBlockPosition();
                System.out.println(
                    "TLMNH_N_AIR_SENSING_TRACE age=" + age
                        + " grants="
                        + budget.admitted(Kind.AIR)
                        + " baseline="
                        + airGrants[0]
                        + " used="
                        + budget.used(
                            helper.getWorld()
                                .getTotalWorldTime())
                        + " going="
                        + core.actor()
                            .isGoingToBreath()
                        + " target="
                        + (point == null ? "null" : point.x + "," + point.y + "," + point.z)
                        + " noPath="
                        + f.navigation.noPath()
                        + " outcome="
                        + core.navigation()
                            .outcome()
                        + " searches="
                        + f.navigation.searchCount()
                        + " feetY="
                        + f.maid.posY);
            }
            if (!core.actor()
                .isGoingToBreath()
                || core.actor()
                    .peekWalkTarget() == null
                || f.navigation.noPath()) return false;
            helper.assertTrue(
                budget.admitted(Kind.AIR) > airGrants[0] && !f.navigation.noPath()
                    && budget.used(
                        helper.getWorld()
                            .getTotalWorldTime())
                        <= 1,
                "Granted actual air search failed to install a breathing route within quota");
            return true;
        });
    }

    private static void configure(GameTestHelper helper) {
        int previous = MaidPerceptionConfig.scansPerTick;
        MaidPerceptionConfig.scansPerTick = 1;
        MaidWorldSensingBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidPerceptionConfig.scansPerTick = previous;
            MaidWorldSensingBudget.unload(helper.getWorld());
        });
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, boolean wet) {
        var f = new ModernCoreTests.Fixture(helper, wet, 1, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setPickup(false);
        f.owner.setPosition(f.x + 1.5, f.y + (wet ? 6 : 1), f.z + 1.5);
        return f;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, EntityMaid maid) {
        helper.assertTrue(
            maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Enhanced sensing selection failed");
        return maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void ready(GameTestHelper helper, EntityMaid maid, EnhancedCoreBrain core) {
        var b = maid.getBrainCoordinator();
        helper.assertTrue(
            maid.isEntityAlive() && b.peekEnhancedCore() == core
                && b.peekPolicyRuntime()
                    .isReady()
                && b.peekFaithfulBrain() == null
                && b.getFollowController()
                    .getTicks() == 0
                && b.getCombatController()
                    .getTicks() == 0,
            "Enhanced sensing faulted or used another root");
    }
}
