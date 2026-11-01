package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidClimbActor;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernClimbTests {

    private ModernClimbTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 320, batch = "modern_climb_roundtrip")
    public static void originalTaskClimbsToPlatformAndReturnsDown(GameTestHelper helper) {
        // 超过实际3格安全跌落范围，要求返回路径也包含梯子竖直边
        var f = fixture(helper, 6, 8);
        boolean[] climbed = { false }, down = { false }, returned = { false }, verticalReturn = { false };
        helper.onEachTick("natural original climbing round trip", () -> {
            f.check();
            climbed[0] |= f.maid.isCanClimb();
            if (!returned[0] && f.maid.posX >= f.x + 6
                && f.maid.posY >= f.y + 6.9
                && f.maid.onGround
                && f.core.getMoveState()
                    .getStatus() == FollowMoveState.Status.STOPPED
                && f.core.getActor()
                    .peekWalkTarget() == null) {
                helper.assertTrue(climbed[0], "Platform was reached without original climb control");
                returned[0] = true;
                f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
            }
            if (returned[0] && f.maid.isCanClimb() && f.maid.isSneaking() && f.maid.motionY < 0) down[0] = true;
            var path = f.navigation.getPath();
            if (returned[0] && path != null) for (int i = 1; i < path.getCurrentPathLength(); i++) {
                var before = path.getPathPointFromIndex(i - 1);
                var after = path.getPathPointFromIndex(i);
                if (before.xCoord == after.xCoord && before.zCoord == after.zCoord && after.yCoord < before.yCoord)
                    verticalReturn[0] = true;
            }
            trace(f, "roundtrip");
        });
        helper.succeedWhen(
            () -> returned[0] && down[0]
                && verticalReturn[0]
                && f.maid.posX < f.x + 4
                && f.maid.posY < f.y + 1.2
                && !f.maid.isCanClimb()
                && !f.maid.isSneaking());
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "modern_climb_permission")
    public static void disabledClimbingDoesNotAscendAndReenabledTaskUsesRealRoute(GameTestHelper helper) {
        var f = fixture(helper);
        f.maid.getConfigManager()
            .setActiveClimbing(false);
        boolean[] enabled = { false }, climbed = { false };
        helper.onEachTick("climbing permission changes with fresh movement", () -> {
            f.check();
            int tick = f.maid.ticksExisted;
            if (!enabled[0]) {
                helper.assertTrue(f.maid.posY < f.y + 1.3 && !f.maid.isCanClimb(), "Disabled climbing still ascended");
                if (tick >= 60) {
                    enabled[0] = true;
                    f.maid.getConfigManager()
                        .setActiveClimbing(true);
                    f.owner.setPosition(f.maid.posX + 6, f.y + 4, f.z + 4.5);
                }
            } else climbed[0] |= f.maid.isCanClimb();
            trace(f, "permission");
        });
        helper.succeedWhen(() -> enabled[0] && climbed[0] && f.maid.posY >= f.y + 3.9 && f.maid.onGround);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "modern_climb_cancel")
    public static void sittingStopsLiveClimbAndOldPathViewCannotWriteSuccessor(GameTestHelper helper) {
        var f = fixture(helper);
        int[] stopped = { -1 };
        boolean[] checked = { false };
        MaidClimbActor.ClimbPath[] old = { null };
        helper.onEachTick("live original climb cancellation and stale writes", () -> {
            f.check();
            if (stopped[0] < 0 && f.maid.isCanClimb() && !f.navigation.noPath()) {
                old[0] = f.core.getActor()
                    .climbPath();
                helper.assertTrue(old[0] != null && !old[0].done(), "Climb cancellation had no live path");
                f.maid.setSitting(true);
                stopped[0] = f.maid.ticksExisted;
            }
            if (stopped[0] >= 0 && f.maid.ticksExisted > stopped[0]) {
                helper.assertTrue(
                    !f.maid.isCanClimb() && !f.maid.isSneaking() && f.navigation.noPath(),
                    "Sitting retained climbing motion state or path");
            }
            if (checked[0] || stopped[0] < 0 || f.maid.ticksExisted < stopped[0] + 3) return;
            var foreign = f.navigation.createPath(f.x + 10, f.y + 4, f.z + 4, 64, 0);
            helper.assertTrue(foreign != null && f.navigation.setPath(foreign, .5), "Foreign path was not installed");
            boolean expired = false;
            try {
                old[0].index(0);
            } catch (IllegalStateException expected) {
                expired = true;
            }
            helper.assertTrue(expired && f.navigation.getPath() == foreign, "Old climb view changed successor path");
            f.core.cancel();
            Object successor = new Object();
            helper
                .assertTrue(f.navigation.claimBrainTarget(successor, new Object(), new MaidPathNavigator.BrainTarget() {

                    public boolean isCurrent() {
                        return true;
                    }

                    public Point currentPosition() {
                        return null;
                    }
                }), "Foreign climbing state owner was not installed");
            f.maid.setCanClimb(true);
            f.maid.setSneaking(true);
            f.core.cancel();
            helper.assertTrue(
                f.maid.isCanClimb() && f.maid.isSneaking() && f.navigation.getPath() == foreign,
                "Late old cancellation erased successor climbing state");
            f.navigation.releaseBrainTarget(successor);
            f.maid.setCanClimb(false);
            f.maid.setSneaking(false);
            checked[0] = true;
            System.out.println("TLMNH_CLIMB101_CANCEL_PASS tick=" + f.maid.ticksExisted);
        });
        helper.succeedWhen(() -> checked[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        return fixture(helper, 3, 7);
    }

    static ModernCoreTests.Fixture fixture(GameTestHelper helper, int ladderHeight, int range) {
        // 原任务start会将y固定到当前方块底部，最多一个方块的有界居中不算主人传送
        var f = new ModernCoreTests.Fixture(helper, false, 2.25, range);
        MaidHomeConfig.workRange = range;
        for (int x = 6; x < 20; x++)
            for (int y = 1; y <= ladderHeight; y++) for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, Blocks.glass);
        for (int y = 1; y <= ladderHeight; y++) helper.getWorld()
            .setBlock(f.x + 5, f.y + y, f.z + 4, Blocks.ladder, 4, 2);
        f.owner.setPosition(f.x + 8.5, f.y + ladderHeight + 1, f.z + 4.5);
        boolean originalFullBox = net.minecraftforge.common.ForgeModContainer.fullBoundingBoxLadders;
        helper.onEachTick(
            "global ladder setting remains owned by the modpack",
            () -> helper.assertTrue(
                net.minecraftforge.common.ForgeModContainer.fullBoundingBoxLadders == originalFullBox,
                "Modern climbing changed the global Forge ladder setting"));
        System.out.println("TLMNH_CLIMB101_LADDER_INPUT fullBox=" + originalFullBox);
        return f;
    }

    private static void trace(ModernCoreTests.Fixture f, String kind) {
        if (f.maid.ticksExisted % 20 != 0) return;
        var path = f.navigation.getPath();
        System.out.println(
            "TLMNH_CLIMB101_TRACE kind=" + kind
                + " tick="
                + f.maid.ticksExisted
                + " xyz="
                + f.maid.posX
                + ","
                + f.maid.posY
                + ","
                + f.maid.posZ
                + " climb="
                + f.maid.isCanClimb()
                + " sneak="
                + f.maid.isSneaking()
                + " fallDelay="
                + f.maid.getClimbFallDelayTicks()
                + " path="
                + (path == null ? "null" : path.getCurrentPathIndex() + "/" + path.getCurrentPathLength()));
    }
}
