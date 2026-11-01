package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathEntity;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedWaterTests {

    private EnhancedWaterTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 120, batch = "enhanced_water_surface")
    public static void surfaceWithoutGoalFloatsNaturallyAndRetiredActorCannotClearSuccessorJump(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 1, z, Blocks.water);
        f.maid.setPosition(f.x + 2.5, f.y + 1.2, f.z + 4.5);
        f.owner.setPosition(f.x + 3.5, f.y + 1.2, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
        var first = select(helper, f);
        var active = new EnhancedCoreBrain[] { first };
        int[] phase = { 0 }, changed = { 0 };
        long[] pausedRequests = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, active[0]);
            int age = f.maid.ticksExisted;
            var aquatic = f.maid.getAquaticController();
            if (phase[0] == 0) {
                if (first.buoyancy()
                    .requests() == 0 || f.maid.posY < f.y + 1.22) return false;
                helper.assertTrue(
                    first.selectedKind() == null && f.navigation.noPath()
                        && first.buoyancy()
                            .eligibleFrames()
                            >= first.buoyancy()
                                .requests(),
                    "Surface floating required an invented movement goal or route");
                aquatic.requestJump();
                first.cancel();
                helper.assertTrue(!pending(aquatic), "Current actor cancellation retained its own pending native jump");
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Intermediate profile was not selected");
                active[0] = select(helper, f);
                helper.assertTrue(active[0] != first, "Successor reused the old enhanced root");
                phase[0] = 1;
                changed[0] = age;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                if (active[0].buoyancy()
                    .requests() == 0) return false;
                aquatic.requestJump();
                first.cancel();
                helper.assertTrue(pending(aquatic), "Retired actor cleared a later actor's native jump request");
                aquatic.clearPendingJump();
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                pausedRequests[0] = active[0].buoyancy()
                    .requests();
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                helper.assertTrue(
                    active[0].buoyancy()
                        .requests() == pausedRequests[0] && !pending(aquatic),
                    "Suspended root created or retained a passive jump");
                if (age < changed[0] + 4) return false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 3;
                return false;
            }
            return active[0].buoyancy()
                .requests() > pausedRequests[0] && f.maid.posY > f.y + 1.2;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_water_navigation")
    public static void submergedFollowUsesActualWaterPathWithoutPassiveJumpAndRestartsAfterPause(
        GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        var core = select(helper, f);
        double originalDistance = f.maid.getDistanceSqToEntity(f.owner);
        int[] phase = { 0 }, changed = { 0 };
        long[] searches = { 0 }, requests = { 0 };
        PathEntity[] previous = { null };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (!(f.navigation.getPath() instanceof MaidModernPath path) || path.isFinished()
                    || !f.navigation.usesWaterNavigation()
                    || f.maid.getDistanceSqToEntity(f.owner) >= originalDistance - 4) return false;
                helper.assertTrue(
                    core.buoyancy()
                        .requests() == 0,
                    "Submerged active route was mixed with passive surface jumping");
                previous[0] = path;
                searches[0] = f.navigation.searchCount();
                requests[0] = core.buoyancy()
                    .requests();
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    f.navigation.noPath() && core.actor()
                        .peekWalkTarget() == null,
                    "Pause retained the old water route or WALK target");
                phase[0] = 1;
                changed[0] = age;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    f.navigation.noPath() && core.buoyancy()
                        .requests() == requests[0],
                    "Paused water actor continued navigating or jumping");
                if (age < changed[0] + 4) return false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 2;
                return false;
            }
            var path = f.navigation.getPath();
            var memory = core.actor()
                .peekPathMemory();
            return path instanceof MaidModernPath current && !current.isFinished()
                && path != previous[0]
                && f.navigation.usesWaterNavigation()
                && f.navigation.searchCount() > searches[0]
                && memory != null
                && memory.getIdentity() == path;
        });
    }

    private static boolean pending(Object aquatic) {
        try {
            var field = aquatic.getClass()
                .getDeclaredField("pendingJump");
            field.setAccessible(true);
            return field.getBoolean(aquatic);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect actual pending native jump", failure);
        }
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Independent enhanced selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void check(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        f.check();
        helper.assertTrue(
            f.maid.getBrainCoordinator()
                .peekEnhancedCore() == core
                && f.maid.getBrainCoordinator()
                    .peekFaithfulBrain() == null
                && f.maid.getBrainCoordinator()
                    .getFollowController()
                    .getTicks() == 0,
            "Enhanced water behavior delegated to F or legacy root");
    }
}
