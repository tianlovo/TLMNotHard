package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreComponent;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernDoorTests {

    private ModernDoorTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "modern_door_permissions")
    public static void naturalDoorAndGateUseIndependentPermissionsAndActualAnimatedClosure(GameTestHelper helper) {
        var f = corridor(helper);
        door(helper, 4);
        helper.getWorld()
            .setBlock(f.x + 8, f.y + 1, f.z + 3, Blocks.fence_gate, 1, 2);
        f.maid.getConfigManager()
            .setOpenDoor(false);
        f.maid.getConfigManager()
            .setOpenFenceGate(false);
        int[] phase = { 0 }, changed = { -1 };
        boolean[] woodOpened = { false }, gateOpened = { false }, done = { false };
        helper.onEachTick("natural corridor, capabilities and real animated door state", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            woodOpened[0] |= open(state(helper, 4));
            gateOpened[0] |= open(state(helper, 8));
            if (phase[0] == 0) {
                helper.assertTrue(
                    !woodOpened[0] && !gateOpened[0] && f.maid.posX < f.x + 4.31,
                    "Disabled capabilities opened or crossed a barrier");
                if (age >= 30) {
                    f.maid.getConfigManager()
                        .setOpenDoor(true);
                    phase[0] = 1;
                }
            } else if (phase[0] == 1 && f.maid.posX > f.x + 5.2) {
                helper.assertTrue(
                    woodOpened[0] && !gateOpened[0],
                    "Independent door capability did not permit only wood");
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2) {
                helper.assertTrue(
                    !gateOpened[0] && f.maid.posX < f.x + 8.31,
                    "Wood permission also opened or crossed disabled gate");
                if (age >= changed[0] + 30) {
                    f.maid.getConfigManager()
                        .setOpenFenceGate(true);
                    f.owner.setPosition(f.x + 18.5, f.y + 1, f.z + 3.5);
                    phase[0] = 3;
                }
            } else if (phase[0] == 3 && f.maid.posX > f.x + 10.8
                && state(helper, 4).equals("CLOSED")
                && state(helper, 8).equals("CLOSED")) {
                    var actor = f.core.getActor();
                    helper.assertTrue(
                        woodOpened[0] && gateOpened[0]
                            && actor.doorOpenRequests() >= 2
                            && actor.doorCloseRequests() >= 2
                            && f.navigation.searchCount() > 0,
                        "Natural route bypassed original door task or native closure");
                    done[0] = true;
                    System.out.println(
                        "TLMNH_DOOR114_CAPABILITIES_PASS age=" + age
                            + " opens="
                            + actor.doorOpenRequests()
                            + " closes="
                            + actor.doorCloseRequests());
                }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 320, batch = "modern_door_handoff")
    public static void twoNaturalMaidsHandDoorMemoryToFollowerAndRefreshKeepsRemainingSet(GameTestHelper helper) {
        var f = corridor(helper);
        place(f, 3.5);
        door(helper, 7);
        var follower = new EntityMaid(helper.getWorld());
        helper.assertTrue(follower.bindTo(f.owner.getUniqueID()), "Second maid binding failed");
        follower.setPickup(false);
        helper.spawnEntity(follower, 2.5, 1, 3.5);
        var second = new ModernFollowCoreComponent(follower);
        follower.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(second);
        follower.setSchedule(MaidSchedule.ALL);
        follower.getConfigManager()
            .setOpenDoor(true);
        helper.afterTest(() -> {
            follower.getBrainCoordinator()
                .setSuspended(true);
            follower.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(follower);
        });
        boolean[] refreshed = { false }, handed = { false }, done = { false };
        helper.onEachTick("two natural paths, remembered-door copy and source same-type handoff", () -> {
            f.check();
            helper.assertTrue(
                follower.isEntityAlive() && follower.getHealth() == 20
                    && follower.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .isReady()
                    && second.getActor()
                        .teleportAttempts() == 0,
                "Follower policy or physical survival failed");
            var actor = f.core.getActor();
            if (!refreshed[0] && actor.rememberedDoorCount() > 0 && actor.doorOpenRequests() > 0) {
                int remembered = actor.rememberedDoorCount();
                f.maid.setSchedule(MaidSchedule.ALL);
                f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 3.5);
                helper.assertTrue(
                    actor.rememberedDoorCount() == remembered,
                    "Source Brain refresh erased DOORS_TO_CLOSE");
                refreshed[0] = true;
                return;
            }
            if (refreshed[0] && actor.doorOpenRequests() > 0
                && actor.doorCloseRequests() == 0
                && actor.rememberedDoorCount() == 0
                && open(state(helper, 7))
                && actor.otherMaidsComingThrough(actor.doorPosition(new Point(f.x + 7, f.y + 1, f.z + 3))))
                handed[0] = true;
            if (handed[0] && f.maid.posX > f.x + 8.5
                && follower.posX > f.x + 8.5
                && state(helper, 7).equals("CLOSED")
                && second.getActor()
                    .doorCloseRequests() > 0) {
                done[0] = true;
                System.out.println(
                    "TLMNH_DOOR114_HANDOFF_PASS age=" + f.maid.ticksExisted
                        + " firstCloses="
                        + actor.doorCloseRequests()
                        + " followerCloses="
                        + second.getActor()
                            .doorCloseRequests());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static ModernCoreTests.Fixture corridor(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 12);
        place(f, 1.5);
        f.owner.setPosition(f.x + 14.5, f.y + 1, f.z + 3.5);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.getConfigManager()
            .setOpenDoor(true);
        f.maid.getConfigManager()
            .setOpenFenceGate(true);
        for (int x = 1; x < 20; x++) {
            for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
            for (int y = 1; y < 10; y++) {
                helper.setBlock(x, y, 2, Blocks.stone);
                helper.setBlock(x, y, 4, Blocks.stone);
            }
        }
        System.out.println(
            "TLMNH_DOOR114_NATIVE_TYPES door=" + Blocks.wooden_door.getClass()
                .getName()
                + " gate="
                + Blocks.fence_gate.getClass()
                    .getName());
        return f;
    }

    private static void place(ModernCoreTests.Fixture f, double x) {
        f.maid.setPosition(f.x + x, f.y + 1, f.z + 3.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
    }

    private static void door(GameTestHelper helper, int x) {
        var p = helper.absolute(x, 1, 3);
        helper.getWorld()
            .setBlock(p.x(), p.y(), p.z(), Blocks.wooden_door, 0, 2);
        helper.getWorld()
            .setBlock(p.x(), p.y() + 1, p.z(), Blocks.wooden_door, 8, 2);
    }

    private static boolean open(String state) {
        return state.equals("OPENED") || state.equals("OPENING");
    }

    private static String state(GameTestHelper helper, int x) {
        var p = helper.absolute(x, 1, 3);
        var tile = helper.getWorld()
            .getTileEntity(p.x(), p.y(), p.z());
        if (tile != null) {
            try {
                return ((Enum<?>) tile.getClass()
                    .getMethod("getState")
                    .invoke(tile)).name();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Actual animated door state unavailable", failure);
            }
        }
        return (helper.getWorld()
            .getBlockMetadata(p.x(), p.y(), p.z()) & 4) != 0 ? "OPENED" : "CLOSED";
    }
}
