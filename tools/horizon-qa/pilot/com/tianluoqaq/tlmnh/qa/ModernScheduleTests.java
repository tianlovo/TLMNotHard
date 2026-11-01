package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernScheduleTests {

    private ModernScheduleTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 220, batch = "modern_schedule_latch")
    public static void restrictionLatchesAfterPhysicsAndNaturalReturnKeepsRuntimeOutOfSave(GameTestHelper helper) {
        var f = fixture(helper, 1);
        MaidHomeConfig.workRange = 8;
        MaidHomeConfig.idleRange = 6;
        var area = f.maid.getHomeArea();
        place(f, 4.5);
        helper.assertTrue(area.restrictionRadius() == 8, "Birth non-home range did not use its own configuration");
        helper.assertTrue(area.setEnabled(true), "Work home setup failed");
        place(f, 14.5);
        helper.assertTrue(area.capture(MaidLifeRules.Activity.IDLE), "Idle anchor setup failed");
        place(f, 4.5);
        int[] phase = { 0 }, disabled = { -1 };
        Object[] token = { null };
        boolean[] postPhase = { false }, realPath = { false }, done = { false };
        helper.onEachTick("latched restriction, distinct post-physics phase and natural return", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            var state = f.core.getActivities();
            if (phase[0] == 0 && age >= 25) {
                helper.assertTrue(state.active() == Activity.WORK, "Initial activity did not settle to WORK");
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
                    "Time change invalidated the modern context");
                if (age < 40) helper.assertTrue(
                    area.restrictionCenter().x == f.x + 4 && area.restrictionRadius() == 8
                        && state.active() == Activity.WORK,
                    "Restriction followed day time before its entity phase");
                if (age == 40) {
                    var walk = actor.peekWalkTarget();
                    helper.assertTrue(
                        area.restrictionCenter().x == f.x + 14 && area.restrictionRadius() == 6
                            && state.active() == Activity.WORK
                            && walk != null
                            && walk.currentBlockPosition().x == f.x + 14
                            && walk.getSpeedModifier() == .7F
                            && walk.getCloseEnoughDistance() == 3
                            && f.navigation.noPath()
                            && f.core.getMoveState()
                                .getStatus() == FollowMoveState.Status.STOPPED,
                        "Entity age40 did not write its goal after this frame's move sink/physics");
                    postPhase[0] = true;
                }
                if (age == 41) {
                    helper.assertTrue(!f.navigation.noPath(), "Post-phase WALK did not start a real path next tick");
                    realPath[0] = true;
                }
                if (age >= 43) helper.assertTrue(state.active() == Activity.IDLE, "Brain schedule did not catch up");
                if (realPath[0] && age > 43
                    && actor.peekWalkTarget() == null
                    && f.core.getMoveState()
                        .getStatus() == FollowMoveState.Status.STOPPED) {
                    helper.assertTrue(
                        f.maid.posX > f.x + 9 && actor.homeTeleportAttempts() == 0,
                        "Threshold equality did not finish a natural home return");
                    helper.assertTrue(
                        area.setEnabled(false) && area.restrictionRadius() == 6,
                        "Disabling home discarded the last restriction latch");
                    disabled[0] = age;
                    phase[0] = 2;
                }
            } else if (phase[0] == 2 && age > disabled[0]) {
                helper.assertTrue(
                    actor.getRestrictRadius() == 6 && area.withinRestriction(f.x + 100, f.y, f.z),
                    "Home-off range or unrestricted movement differs from the source");
                var saved = new NBTTagCompound();
                area.save(saved);
                helper.assertFalse(
                    saved.getCompoundTag("TLMNHHome")
                        .getString("Settings")
                        .contains("|"),
                    "Runtime restriction leaked into persisted home settings");
                var restored = new EntityMaid(helper.getWorld());
                try {
                    restored.getHomeArea()
                        .load(saved);
                    helper.assertTrue(
                        !restored.getHomeArea()
                            .enabled()
                            && restored.getHomeArea()
                                .restrictionRadius() == 8
                            && restored.getHomeArea()
                                .settings()
                                .encode()
                                .equals(
                                    area.settings()
                                        .encode()),
                        "Home reload persisted the runtime latch or lost original settings");
                } finally {
                    restored.setDead();
                }
                done[0] = true;
                System.out.println("TLMNH_SCHEDULE105_LATCH_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> postPhase[0] && realPath[0] && done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 125, batch = "modern_schedule_teleport")
    public static void farHomeUsesIntegerTeleportButExistingHomeWalkKeepsItsRoute(GameTestHelper helper) {
        var f = fixture(helper, 10000);
        farHome(f);
        boolean[] teleported = { false }, walked = { false }, done = { false };
        int[] attempts = { 0 };
        helper.onEachTick("original home teleport geometry and same-center walk suppression", () -> {
            f.check();
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;
            if (!teleported[0] && age < 40) helper.assertTrue(
                actor.homeTeleportAttempts() == 0 && Math.abs(f.maid.posX - f.x - 17.5) < .001,
                "Home return ran before entity age40");
            if (!teleported[0] && age == 40) {
                helper.assertTrue(
                    actor.homeTeleportAttempts() >= 1 && actor.homeTeleportAttempts() <= 10
                        && actor.homeTeleportSuccesses() == 1
                        && f.maid.posX >= f.x + 1
                        && f.maid.posX <= f.x + 7
                        && f.maid.posZ >= f.z + 1
                        && f.maid.posZ <= f.z + 7
                        && f.maid.posX == Math.floor(f.maid.posX)
                        && f.maid.posZ == Math.floor(f.maid.posZ)
                        && f.maid.posY == f.y + 1
                        && !helper.getWorld()
                            .isAnyLiquid(f.maid.boundingBox)
                        && actor.peekWalkTarget() == null
                        && actor.peekPathMemory() == null
                        && f.navigation.noPath(),
                    "Far return did not use original integer candidates/support/collision and stop-navigation semantics");
                attempts[0] = actor.homeTeleportAttempts();
                // 显式外部位移是此场景输入，用于区别已有同中心WALK分支；之后只靠原生tick移动
                // 控制输入减慢本段，保证age80仍在远处，实际检验已有同中心WALK抑制传送
                actor.setPositionWalkAndLook(new Point(f.x + 4, f.y + 1, f.z + 4), .1F, 3);
                place(f, 17.5);
                teleported[0] = true;
            } else if (teleported[0]) {
                if (!f.navigation.noPath()) walked[0] = true;
                if (age == 80) {
                    double dx = Math.floor(f.maid.posX) - f.x - 4;
                    helper.assertTrue(
                        dx * dx > 49,
                        "Slow-walk fixture reached home before its far-walk suppression check");
                    helper.assertTrue(
                        actor.peekWalkTarget() != null && actor.walkTargetsRestrictionCenter()
                            && actor.homeTeleportAttempts() == attempts[0]
                            && actor.homeTeleportSuccesses() == 1
                            && !f.navigation.noPath(),
                        "Existing center WALK was replaced by another far teleport");
                    done[0] = true;
                    System.out.println("TLMNH_SCHEDULE105_TELEPORT_PASS tick=" + age + " attempts=" + attempts[0]);
                }
            }
        });
        helper.succeedWhen(() -> teleported[0] && walked[0] && done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 110, batch = "modern_schedule_reject_cancel")
    public static void liquidRejectsAllTenCandidatesAndCancellationSuppressesPostLivingPhase(GameTestHelper helper) {
        var f = fixture(helper, 1);
        farHome(f);
        for (int x = 1; x <= 7; x++)
            for (int z = 1; z <= 7; z++) for (int y = 1; y <= 4; y++) helper.setBlock(x, y, z, Blocks.water);
        for (int z = 1; z <= 7; z++) for (int y = 1; y <= 5; y++) helper.setBlock(8, y, z, Blocks.stone);
        f.maid.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(new MaidPolicyRuntime.AfterNavigationActions() {

                public void tick(Token token) {
                    f.core.tick(token);
                    if (f.maid.ticksExisted == 80) f.maid.getBrainCoordinator()
                        .setSuspended(true);
                }

                public void afterLivingUpdate(Token token) {
                    f.core.afterLivingUpdate(token);
                }

                public void cancel() {
                    f.core.cancel();
                }
            });
        boolean[] rejected = { false }, done = { false };
        helper.onEachTick("liquid rollback and cancellation between Brain and entity post-phase", () -> {
            f.check();
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;
            if (age == 40) {
                helper.assertTrue(
                    actor.homeTeleportAttempts() == 10 && actor.homeTeleportSuccesses() == 0
                        && actor.homeTeleportLiquidRejects() > 0
                        && actor.peekWalkTarget() == null
                        && actor.peekPathMemory() == null
                        && f.navigation.noPath(),
                    "Liquid candidates did not fail ten attempts without inventing a WALK fallback");
                rejected[0] = true;
            }
            if (age >= 40) helper.assertTrue(
                Math.abs(f.maid.posX - f.x - 17.5) < .001 && f.maid.posY == f.y + 1
                    && actor.homeTeleportAttempts() == 10,
                "Rejected/cancelled teleport changed position or ran an old entity phase");
            if (age >= 83) {
                boolean stale = false;
                try {
                    f.core.getActivities()
                        .active();
                } catch (IllegalStateException expected) {
                    stale = true;
                }
                helper.assertTrue(
                    f.maid.getBrainCoordinator()
                        .isSuspended() && stale
                        && f.navigation.noPath()
                        && f.maid.getHomeArea()
                            .restrictionRadius() == 3,
                    "Cancellation retained an actor/navigation or discarded entity restriction metadata");
                done[0] = true;
                System.out.println(
                    "TLMNH_SCHEDULE105_REJECT_CANCEL_PASS tick=" + age
                        + " liquid="
                        + actor.homeTeleportLiquidRejects());
            }
        });
        helper.succeedWhen(() -> rejected[0] && done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, double displacement) {
        int idle = MaidHomeConfig.idleRange, sleep = MaidHomeConfig.sleepRange;
        var f = new ModernCoreTests.Fixture(helper, false, displacement, 8);
        helper.afterTest(() -> {
            MaidHomeConfig.idleRange = idle;
            MaidHomeConfig.sleepRange = sleep;
        });
        f.maid.setSchedule(MaidSchedule.DAY);
        f.owner.setPosition(f.x + 4.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static void farHome(ModernCoreTests.Fixture f) {
        MaidHomeConfig.workRange = 3;
        place(f, 4.5);
        f.helper.assertTrue(
            f.maid.getHomeArea()
                .setEnabled(true),
            "Far home setup failed");
        place(f, 17.5);
    }

    private static void place(ModernCoreTests.Fixture f, double x) {
        f.maid.setPosition(f.x + x, f.y + 1, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
    }
}
