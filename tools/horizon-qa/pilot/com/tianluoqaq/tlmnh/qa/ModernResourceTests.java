package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.InvalidationReason;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernResourceTests {

    private ModernResourceTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "modern_resource_pickup")
    public static void dynamicDropUsesRealSinkAndContactConservesItemsAndExperience(GameTestHelper helper) {
        var f = fixture(helper);
        var item = drop(helper, 8.5, 1, 4.5, 3);
        var xp = new EntityXPOrb(helper.getWorld(), 0, 0, 0, 5);
        helper.spawnEntity(xp, 11.5, 1, 6.5);
        xp.motionX = xp.motionY = xp.motionZ = 0;
        helper.afterTest(() -> {
            xp.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(xp);
        });
        boolean[] moved = { false }, replanned = { false };
        WalkTarget[] original = { null };
        helper.onEachTick("real dynamic item target and contact transactions", () -> {
            f.check();
            var actor = f.core.getActor();
            var path = f.navigation.getPath();
            if (f.maid.ticksExisted <= 40 && f.maid.ticksExisted % 10 == 0) {
                var goal = actor.peekWalkTarget() == null ? null
                    : actor.peekWalkTarget()
                        .currentBlockPosition();
                System.out.println(
                    "TLMNH_PICKUP103_INPUT tick=" + f.maid.ticksExisted
                        + " item="
                        + (item.posX - f.x)
                        + ","
                        + (item.posY - f.y)
                        + ","
                        + (item.posZ - f.z)
                        + " goal="
                        + (goal == null ? "null" : (goal.x - f.x) + "," + (goal.y - f.y) + "," + (goal.z - f.z))
                        + " pathGoal="
                        + (path instanceof MaidModernPath route
                            ? (route.result.targetX - f.x) + "," + (route.result.targetY - f.y)
                            : "none")
                        + " moved="
                        + moved[0]
                        + " diamonds="
                        + diamonds(f)
                        + " xp="
                        + f.maid.getExperience());
            }
            if (!moved[0] && path instanceof MaidModernPath route
                && route.result.targetX == (int) Math.floor(item.posX)) {
                original[0] = actor.peekWalkTarget();
                item.setPosition(f.x + 12.5, f.y + 1.25, f.z + 4.5);
                item.motionX = item.motionY = item.motionZ = 0;
                moved[0] = true;
            }
            if (moved[0] && !item.isDead && actor.peekWalkTarget() == original[0]) {
                helper.assertTrue(
                    actor.peekWalkTarget()
                        .currentBlockPosition().x == (int) Math.floor(item.posX),
                    "Item WALK_TARGET froze the original block position");
            }
            if (moved[0] && path instanceof MaidModernPath route
                && route.result.targetX == f.x + 12
                && route.canReach()) replanned[0] = true;
            int worldItems = item.isDead ? 0 : item.getEntityItem().stackSize;
            helper.assertTrue(diamonds(f) + worldItems == 3, "Natural pickup duplicated or lost diamonds");
            helper.assertTrue(
                f.maid.getExperience() + (xp.isDead ? 0 : xp.xpValue) == 5,
                "Natural pickup lost experience");
            if (f.maid.ticksExisted == 40)
                helper.assertTrue(moved[0], "Dynamic input was not scheduled; inspect actual drop position");
        });
        helper.succeedWhen(
            () -> moved[0] && replanned[0]
                && item.isDead
                && xp.isDead
                && diamonds(f) == 3
                && f.maid.getExperience() == 5);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 360, batch = "modern_resource_bfs_cancel")
    public static void bfsSkipsVisibleUnreachableDropAndSittingKeepsWorldStock(GameTestHelper helper) {
        var f = fixture(helper);
        // 仅布置阶段移动女仆到悬沟左侧，后续全部由生产实体tick推进
        f.maid.setPosition(f.x + 7.5, f.y + 1, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
        f.owner.setPosition(f.x + 7.5, f.y + 1, f.z + 1.5);
        for (int x = 9; x <= 12; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.air);
        var unreachable = drop(helper, 13.5, 1, 4.5, 2);
        var reachable = drop(helper, 1.5, 1, 6.5, 2);
        for (int y = 1; y <= 5; y++) {
            helper.setBlock(6, y, 7, Blocks.glass);
            helper.setBlock(8, y, 7, Blocks.glass);
            helper.setBlock(7, y, 6, Blocks.glass);
            helper.setBlock(7, y, 7, Blocks.water);
        }
        var wet = drop(helper, 7.5, 1, 7.5, 2);
        int[] phase = { 0 }, stoppedAt = { 0 };
        helper.onEachTick("BFS selection and live movement cancellation conserve resources", () -> {
            f.check();
            var actor = f.core.getActor();
            if (phase[0] == 0 && actor.peekWalkTarget() != null) {
                var candidates = f.core.getPerception()
                    .pickupEntities();
                var goal = actor.peekWalkTarget()
                    .currentBlockPosition();
                System.out.println(
                    "TLMNH_PICKUP103_BFS_INPUT goal=" + (goal.x - f.x)
                        + ","
                        + (goal.y - f.y)
                        + ","
                        + (goal.z - f.z)
                        + " reachable="
                        + (reachable.posX - f.x)
                        + ","
                        + (reachable.posY - f.y)
                        + ","
                        + (reachable.posZ - f.z)
                        + " unreachable="
                        + (unreachable.posX - f.x)
                        + ","
                        + (unreachable.posY - f.y)
                        + ","
                        + (unreachable.posZ - f.z));
                helper.assertTrue(
                    candidates.indexOf(unreachable) >= 0
                        && candidates.indexOf(unreachable) < candidates.indexOf(reachable),
                    "Unreachable candidate was not visible and nearer before BFS selection");
                helper.assertTrue(!candidates.contains(wet), "Water item entered pickup memory");
                helper.assertTrue(
                    actor.peekWalkTarget()
                        .currentBlockPosition().x == (int) Math.floor(reachable.posX),
                    "BFS selected the nearer drop beyond an unwalkable void gap");
                phase[0] = 1;
            }
            if (phase[0] == 1 && f.navigation.getPath() != null && !f.navigation.noPath()) {
                helper.assertTrue(
                    diamonds(f) == 0 && !reachable.isDead,
                    "Cancellation fixture had already picked up its stock");
                f.maid.setSitting(true);
                stoppedAt[0] = f.maid.ticksExisted;
                phase[0] = 2;
            } else if (phase[0] == 2 && f.maid.ticksExisted >= stoppedAt[0] + 3) {
                helper.assertTrue(
                    f.navigation.noPath() && actor.peekWalkTarget() == null
                        && diamonds(f) == 0
                        && reachable.getEntityItem().stackSize == 2
                        && !reachable.isDead,
                    "Sitting retained a live route or consumed uncontacted stock");
                f.maid.setSitting(false);
                phase[0] = 3;
            }
            helper.assertTrue(
                !unreachable.isDead && unreachable.getEntityItem().stackSize == 2,
                "Unreachable drop was remotely consumed");
            helper.assertTrue(
                diamonds(f) + (reachable.isDead ? 0 : reachable.getEntityItem().stackSize) == 2,
                "Cancelled/resumed pickup changed the reachable total");
        });
        helper.succeedWhen(() -> phase[0] == 3 && reachable.isDead && diamonds(f) == 2 && !unreachable.isDead);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_resource_hurt")
    public static void actualDamageHistorySurvivesPolicyResetAndExpiresAtFortyWorldTicks(GameTestHelper helper) {
        var f = fixture(helper);
        f.maid.setPickup(false);
        var attacker = new EntityZombie(helper.getWorld());
        attacker.tasks.taskEntries.clear();
        attacker.targetTasks.taskEntries.clear();
        attacker.func_110163_bv();
        helper.spawnEntity(attacker, 18.5, 1, 6.5);
        helper.afterTest(() -> {
            attacker.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(attacker);
        });
        var hit = DamageSource.causeMobDamage(attacker);
        int[] phase = { 0 };
        long[] hitAt = { 0 }, environmentAt = { -1 }, scanAt = { 0 };
        float[] expectedHealth = { 20 };
        boolean[] reset = { false }, exactForty = { false }, done = { false };
        helper.onEachTick("accepted/rejected damage and independent HurtBy memory lifetime", () -> {
            f.check(expectedHealth[0]);
            long now = helper.getWorld()
                .getTotalWorldTime();
            var p = f.core.getPerception();
            if (phase[0] == 0) {
                helper.assertTrue(f.maid.attackEntityFrom(hit, 1), "Initial real mob damage was rejected");
                expectedHealth[0] = 19;
                hitAt[0] = now;
                helper
                    .assertTrue(!f.maid.attackEntityFrom(DamageSource.fall, 1), "Resistant duplicate hit was accepted");
                helper.assertTrue(f.maid.getMaidLastDamageSource() == hit, "Rejected hit replaced accepted source");
                phase[0] = 1;
            } else if (phase[0] == 1 && p.hurtBy() == hit && p.hurtByEntity() == attacker) {
                if (!reset[0]) {
                    reset[0] = true;
                    f.maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .invalidate(InvalidationReason.RESET);
                    helper.assertTrue(
                        f.maid.getMaidLastDamageSource() == hit,
                        "Policy reset erased entity damage history");
                } else if (now - hitAt[0] > 20) {
                    helper.assertTrue(
                        f.maid.attackEntityFrom(DamageSource.generic, 1),
                        "Environment damage was rejected");
                    expectedHealth[0] = 18;
                    environmentAt[0] = now;
                    phase[0] = 2;
                }
            } else if (phase[0] == 2 && p.hurtBy() == DamageSource.generic) {
                helper.assertTrue(p.hurtByEntity() == attacker, "Environment damage erased live previous attacker");
                attacker.setDead();
                scanAt[0] = p.hurtScanCount();
                phase[0] = 3;
            } else if (phase[0] == 3 && p.hurtScanCount() > scanAt[0]) {
                helper.assertTrue(p.hurtByEntity() == null, "Dead attacker remained after HurtBy scan");
                phase[0] = 4;
            }
            if (environmentAt[0] >= 0) {
                long age = now - environmentAt[0];
                if (age == 40) {
                    helper.assertTrue(
                        f.maid.getMaidLastDamageSource() == DamageSource.generic,
                        "Source expired at forty instead of after forty");
                    exactForty[0] = true;
                } else if (age > 40) {
                    helper.assertTrue(
                        f.maid.getMaidLastDamageSource() == null,
                        "Source survived more than forty world ticks");
                    if (phase[0] == 4 && p.hurtBy() == null && p.hurtByEntity() == null) done[0] = true;
                }
            }
        });
        helper.succeedWhen(() -> done[0] && reset[0] && exactForty[0] && f.maid.getHealth() == 18);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        MaidHomeConfig.workRange = 16;
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        f.maid.setPickup(true);
        return f;
    }

    private static EntityItem drop(GameTestHelper helper, double x, double y, double z, int amount) {
        var item = new EntityItem(helper.getWorld(), 0, 0, 0, new ItemStack(Items.diamond, amount));
        // EntityItem的yOffset为半高，出生时不得令碰撞箱下缘嵌入地板
        helper.spawnEntity(item, x, y + .25, z);
        item.motionX = item.motionY = item.motionZ = 0;
        helper.afterTest(() -> {
            item.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(item);
        });
        return item;
    }

    private static int diamonds(ModernCoreTests.Fixture f) {
        int total = 0;
        var inventory = f.maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var item = inventory.getStackInSlot(slot);
            if (item != null && item.getItem() == Items.diamond) total += item.stackSize;
        }
        return total;
    }
}
