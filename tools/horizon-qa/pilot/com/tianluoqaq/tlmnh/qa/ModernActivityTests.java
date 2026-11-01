package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernActivityTests {

    private ModernActivityTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "modern_activity_flee")
    public static void creeperPanicLeavesSittingRunsActualLandRouteAndResumesAfterCancellation(GameTestHelper helper) {
        var f = fixture(helper);
        place(f, 10.5);
        f.owner.setPosition(f.x + 10.5, f.y + 1, f.z + 1.5);
        f.maid.setSitting(true);
        var creeper = new EntityCreeper(helper.getWorld());
        creeper.tasks.taskEntries.clear();
        creeper.targetTasks.taskEntries.clear();
        creeper.func_110163_bv();
        helper.spawnEntity(creeper, 14.5, 1, 4.5);
        helper.afterTest(() -> {
            creeper.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(creeper);
        });
        int[] phase = { 0 }, stopped = { 0 };
        boolean[] live = { false }, resumed = { false }, done = { false };
        helper.onEachTick("natural panic land route and lifecycle cancellation", () -> {
            f.check();
            if (phase[0] == 1) {
                helper.assertTrue(
                    f.navigation.noPath() && !f.maid.isSitting(),
                    "Suspended panic retained movement or restored sitting");
                if (f.maid.ticksExisted >= stopped[0] + 3) {
                    f.maid.getBrainCoordinator()
                        .setSuspended(false);
                    phase[0] = 2;
                }
                return;
            }
            var state = f.core.getActivities();
            var walk = f.core.getActor()
                .peekWalkTarget();
            if (state.active() == Activity.PANIC && walk != null) {
                helper.assertTrue(
                    !f.maid.isSitting() && walk.getSpeedModifier() == .7F,
                    "Panic did not leave sit or use original speed");
                helper.assertTrue(
                    walk.currentBlockPosition().x < f.x + 10.5,
                    "Land target did not point away from the creeper");
                if (f.navigation.getPath() instanceof MaidModernPath path && path.canReach()) {
                    live[0] = true;
                    if (phase[0] == 0) {
                        f.maid.getBrainCoordinator()
                            .setSuspended(true);
                        boolean retired = false;
                        try {
                            state.active();
                        } catch (IllegalStateException expected) {
                            retired = true;
                        }
                        helper.assertTrue(
                            retired && f.navigation.noPath(),
                            "Retired activity or live path survived cancellation");
                        stopped[0] = f.maid.ticksExisted;
                        phase[0] = 1;
                    } else if (phase[0] == 2) resumed[0] = true;
                }
            }
            if (resumed[0] && f.maid.getDistanceSqToEntity(creeper) > 64
                && f.core.getPerception()
                    .nearestHostile() == null
                && state.active() == Activity.WORK) {
                done[0] = true;
                System.out.println(
                    "TLMNH_ACTIVITY104_FLEE_PASS tick=" + f.maid.ticksExisted
                        + " distance="
                        + f.maid.getDistanceSqToEntity(creeper));
            }
        });
        helper.succeedWhen(() -> live[0] && resumed[0] && done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "modern_activity_hurt")
    public static void attackPermissionBlocksPanicThenPureHurtActivityEndsWithoutInventedThreat(GameTestHelper helper) {
        var f = fixture(helper);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setPose(MaidPose.SLEEPING, true);
        float[] health = { 20 };
        int[] phase = { 0 };
        boolean[] entered = { false }, done = { false };
        helper.onEachTick("task permission, clear-sleep and pure HurtBy activity", () -> {
            f.check(health[0]);
            var activity = f.core.getActivities();
            var memory = f.core.getPerception();
            helper.assertTrue(!f.maid.hasPose(MaidPose.SLEEPING), "Core ClearSleep retained sleep outside REST");
            if (phase[0] == 0) {
                helper.assertTrue(f.maid.attackEntityFrom(DamageSource.generic, 1), "Accepted hurt fixture failed");
                health[0] = 19;
                phase[0] = 1;
            } else if (phase[0] == 1 && memory.hurtBy() != null) {
                helper.assertTrue(activity.active() != Activity.PANIC, "Attack task enabled original idle panic");
                f.maid.setTask(MaidTask.IDLE);
                phase[0] = 2;
            } else if (phase[0] == 2 && activity.active() == Activity.PANIC) {
                entered[0] = true;
                helper.assertTrue(
                    memory.nearestHostile() == null && f.core.getActor()
                        .peekWalkTarget() == null && f.navigation.noPath(),
                    "Pure hurt invented a hostile or flee route absent from the original task");
                phase[0] = 3;
            } else if (phase[0] == 3 && activity.active() == Activity.WORK
                && memory.hurtBy() == null
                && memory.hurtByEntity() == null) {
                    done[0] = true;
                    System.out.println("TLMNH_ACTIVITY104_HURT_PASS tick=" + f.maid.ticksExisted);
                }
        });
        helper.succeedWhen(() -> entered[0] && done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 280, batch = "modern_activity_home")
    public static void scheduleTransitionKeepsPolicyUsesHomeAnchorAndAwaitRejectsOutsideTarget(GameTestHelper helper) {
        var f = fixture(helper);
        MaidHomeConfig.workRange = 8;
        var area = f.maid.getHomeArea();
        place(f, 3.5);
        helper.assertTrue(
            area.setEnabled(true) && area.capture(com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.WORK),
            "Work home anchor setup failed");
        place(f, 12.5);
        helper.assertTrue(
            area.capture(com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.IDLE),
            "Idle anchor setup failed");
        place(f, 17.5);
        helper.assertTrue(
            area.capture(com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.REST),
            "Rest anchor setup failed");
        place(f, 3.5);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 1.5);
        int[] phase = { 0 }, injected = { 0 };
        long[] previousUpdate = { 0 };
        Object[] token = { null };
        boolean[] homeWalk = { false }, done = { false };
        helper.onEachTick("source schedule throttle, home walk and restriction guard", () -> {
            f.check();
            var state = f.core.getActivities();
            var actor = f.core.getActor();
            long now = helper.getWorld()
                .getTotalWorldTime();
            if (phase[0] == 0 && f.maid.ticksExisted >= 25) {
                helper.assertTrue(state.active() == Activity.WORK, "Initial source activity is not WORK");
                previousUpdate[0] = state.lastScheduleUpdate();
                token[0] = f.maid.getBrainCoordinator()
                    .peekPolicyRuntime()
                    .getContext()
                    .getToken();
                helper.getWorld()
                    .setWorldTime(12000);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                helper.assertTrue(
                    token[0] == f.maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getContext()
                        .getToken(),
                    "Prototype home activity scheduling invalidated the modern policy");
                if (now - previousUpdate[0] <= 20)
                    helper.assertTrue(state.active() == Activity.WORK, "Schedule updated before elapsed >20");
                if (state.active() == Activity.IDLE) {
                    var walk = actor.peekWalkTarget();
                    helper.assertTrue(
                        now - previousUpdate[0] == 21 && walk != null
                            && walk.currentBlockPosition().x == f.x + 12
                            && walk.getCloseEnoughDistance() == 3
                            && walk.getSpeedModifier() == .7F,
                        "Schedule transition lost its source throttle/home return target");
                    homeWalk[0] = true;
                    phase[0] = 2;
                }
            } else if (phase[0] == 2 && actor.peekWalkTarget() == null
                && f.core.getMoveState()
                    .getStatus() == FollowMoveState.Status.STOPPED) {
                        helper.assertTrue(f.maid.posX > f.x + 8, "Home target never produced natural movement");
                        actor.setPositionWalkAndLook(new Point(f.x, f.y + 1, f.z + 4), .4F, 0);
                        injected[0] = f.maid.ticksExisted;
                        phase[0] = 3;
                    } else
                if (phase[0] == 3 && f.maid.ticksExisted > injected[0]) {
                    helper.assertTrue(
                        actor.peekWalkTarget() == null && actor.peekPathMemory() == null && f.navigation.noPath(),
                        "Await installed or retained the outside-restriction target");
                    f.maid.setSitting(true);
                    phase[0] = 4;
                } else if (phase[0] == 4 && state.active() == Activity.RIDE_IDLE) {
                    helper.assertTrue(
                        actor.peekWalkTarget() == null && actor.peekPathMemory() == null,
                        "Sitting schedule retained walk/path memories");
                    done[0] = true;
                    System.out.println("TLMNH_ACTIVITY104_HOME_PASS tick=" + f.maid.ticksExisted);
                }
        });
        helper.succeedWhen(() -> homeWalk[0] && done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        f.maid.setSchedule(MaidSchedule.DAY);
        MaidHomeConfig.workRange = 16;
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static void place(ModernCoreTests.Fixture f, double x) {
        f.maid.setPosition(f.x + x, f.y + 1, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
    }
}
