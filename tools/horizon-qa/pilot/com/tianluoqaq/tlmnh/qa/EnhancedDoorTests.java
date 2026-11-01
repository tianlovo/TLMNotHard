package com.tianluoqaq.tlmnh.qa;

import static com.tianluoqaq.tlmnh.qa.ModernDoorTests.corridor;
import static com.tianluoqaq.tlmnh.qa.ModernDoorTests.door;
import static com.tianluoqaq.tlmnh.qa.ModernDoorTests.open;
import static com.tianluoqaq.tlmnh.qa.ModernDoorTests.state;

import net.minecraft.init.Blocks;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidDoorActor.DoorPath;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedDoorTests {

    private EnhancedDoorTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "enhanced_door_permissions")
    public static void independentPermissionsOpenActualDoorAndGateThenBothAnimationsClose(GameTestHelper helper) {
        var f = corridor(helper);
        door(helper, 4);
        helper.getWorld()
            .setBlock(f.x + 8, f.y + 1, f.z + 3, Blocks.fence_gate, 1, 2);
        f.maid.getConfigManager()
            .setOpenDoor(false);
        f.maid.getConfigManager()
            .setOpenFenceGate(false);
        var core = select(helper, f);
        int[] phase = { 0 }, changed = { -1 };
        boolean[] wood = { false }, gate = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            wood[0] |= open(state(helper, 4));
            gate[0] |= open(state(helper, 8));
            if (phase[0] == 0) {
                helper.assertTrue(
                    !wood[0] && !gate[0] && f.maid.posX < f.x + 4.31,
                    "Disabled enhanced capabilities opened or crossed a barrier");
                if (age >= 30) {
                    f.maid.getConfigManager()
                        .setOpenDoor(true);
                    phase[0] = 1;
                }
            } else if (phase[0] == 1 && f.maid.posX > f.x + 5.2) {
                helper.assertTrue(wood[0] && !gate[0], "Door permission also opened the gate");
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2) {
                helper.assertTrue(!gate[0] && f.maid.posX < f.x + 8.31, "Disabled enhanced gate was opened or crossed");
                if (age >= changed[0] + 30) {
                    f.maid.getConfigManager()
                        .setOpenFenceGate(true);
                    f.owner.setPosition(f.x + 18.5, f.y + 1, f.z + 3.5);
                    phase[0] = 3;
                }
            } else if (phase[0] == 3 && f.maid.posX > f.x + 10.8
                && state(helper, 4).equals("CLOSED")
                && state(helper, 8).equals("CLOSED")) {
                    helper.assertTrue(
                        wood[0] && gate[0]
                            && core.actor()
                                .doorOpenRequests() >= 2
                            && core.actor()
                                .doorCloseRequests() >= 2
                            && f.navigation.searchCount() > 0,
                        "Enhanced route bypassed actual door actions or closure");
                    return true;
                }
            return false;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "enhanced_door_cancel")
    public static void suspensionRetiresDoorPathAndMemoryThenFreshRoutePassesAndCloses(GameTestHelper helper) {
        var f = corridor(helper);
        door(helper, 7);
        var core = select(helper, f);
        DoorPath[] old = { null };
        int[] changed = { -1 };
        long[] opens = { 0 }, closes = { 0 };
        boolean[] resumed = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (changed[0] < 0) {
                if (!open(state(helper, 7)) || core.actor()
                    .rememberedDoorCount() == 0) return false;
                old[0] = core.actor()
                    .doorPath();
                helper.assertTrue(old[0] != null && !old[0].done(), "Door had no actual current route");
                opens[0] = core.actor()
                    .doorOpenRequests();
                closes[0] = core.actor()
                    .doorCloseRequests();
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                changed[0] = age;
                return false;
            }
            if (!resumed[0]) {
                helper.assertTrue(
                    f.navigation.noPath() && core.actor()
                        .rememberedDoorCount() == 0
                        && core.actor()
                            .doorOpenRequests() == opens[0]
                        && core.actor()
                            .doorCloseRequests() == closes[0],
                    "Suspended enhanced door action kept a route, memory or native request");
                if (age <= changed[0] + 3) return false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                resumed[0] = true;
                return false;
            }
            boolean expired = false;
            try {
                old[0].next();
            } catch (IllegalStateException expected) {
                expired = true;
            }
            helper.assertTrue(expired, "Old door path acquired resumed authority");
            return f.maid.posX > f.x + 9.5 && state(helper, 7).equals("CLOSED")
                && core.actor()
                    .doorCloseRequests() > closes[0];
        });
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Enhanced door selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && f.maid.getHealth() == 20
                && brain.peekEnhancedCore() == core
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0
                && core.actor()
                    .teleportAttempts() == 0
                && core.actor()
                    .homeTeleportAttempts() == 0,
            "Enhanced door actor faulted, teleported or used another root");
    }
}
