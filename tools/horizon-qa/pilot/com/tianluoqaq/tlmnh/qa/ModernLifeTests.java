package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.BlockBed;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreComponent;
import com.tianluoqaq.tlmnh.common.config.MaidTemptationConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernLifeTests {

    private ModernLifeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "modern_life_bed")
    public static void naturalBedApproachHealsOnceAndScheduleWakeReleasesOccupiedHead(GameTestHelper helper) {
        var f = fixture(helper);
        var bed = bed(helper, 8, 1, 4);
        f.maid.setHealth(12);
        int[] slept = { -1 }, woke = { -1 };
        boolean[] realPath = { false }, done = { false };
        helper.onEachTick("real rest activity, modern route and occupied bed lifecycle", () -> {
            ready(helper, f);
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (!f.navigation.noPath()) realPath[0] = true;
            if (slept[0] < 0 && f.maid.hasPose(MaidPose.SLEEPING)) {
                helper.assertTrue(realPath[0] && f.maid.posX > f.x + 6, "Sleep bypassed the real approach");
                helper.assertTrue(
                    bed.isOwnedBy(f.maid) && occupied(bed)
                        && actor.sleepEntries() == 1
                        && f.maid.getHealth() == f.maid.getMaxHealth()
                        && actor.workTarget() == null
                        && actor.peekWalkTarget() == null
                        && !actor.hasLookTarget(),
                    "Sleep entry lost ownership, source healing or target-memory cleanup");
                slept[0] = age;
            }
            if (slept[0] >= 0 && woke[0] < 0 && age >= slept[0] + 8) {
                helper.assertTrue(actor.sleepEntries() == 1 && bed.isOwnedBy(f.maid), "Sleep repeatedly restarted");
                helper.getWorld()
                    .setWorldTime(12000);
                woke[0] = age;
            }
            if (woke[0] >= 0 && age > woke[0] && !f.maid.hasPose(MaidPose.SLEEPING)) {
                helper.assertTrue(
                    f.core.getActivities()
                        .active() == Activity.WORK && !occupied(bed)
                        && !bed.isOwnedBy(f.maid)
                        && actor.sleepEntries() == 1,
                    "Schedule wake did not release the bed exactly once");
                done[0] = true;
                System.out.println("TLMNH_LIFE108_NATURAL_SLEEP_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 170, batch = "modern_life_cancel")
    public static void occupiedBedIsSkippedBrokenBedWakesAndRetiredWorkMemoryCannotSleep(GameTestHelper helper) {
        // 清理在标准Fixture关闭主人连接/票据前执行
        EntityMaid[] other = { null };
        ModernFollowCoreComponent[] otherCore = { null };
        helper.afterTest(() -> {
            if (other[0] != null) {
                other[0].getBrainCoordinator()
                    .setSuspended(true);
                other[0].setDead();
                helper.getWorld()
                    .removePlayerEntityDangerously(other[0]);
            }
        });
        var f = fixture(helper);
        var closeBed = bed(helper, 3, 1, 4);
        var farBed = bed(helper, 8, 1, 4);
        other[0] = new EntityMaid(helper.getWorld());
        helper.assertTrue(other[0].bindTo(f.owner.getUniqueID()), "Second maid owner binding failed");
        other[0].setSchedule(MaidSchedule.NIGHT);
        other[0].setPickup(false);
        helper.spawnEntity(other[0], 3.5, 1, 2.5);
        other[0].getBrainCoordinator()
            .setSuspended(true);
        helper.assertTrue(closeBed.claim(other[0]), "Exclusive occupied bed setup failed");
        other[0].setPose(MaidPose.SLEEPING, true);
        int[] phase = { 0 }, broken = { 0 };
        boolean[] done = { false };
        helper.onEachTick("occupied nearest candidate, invalidated bed and cancelled TARGET_POS", () -> {
            if (phase[0] < 2) ready(helper, f);
            else helper.assertTrue(
                other[0].getBrainCoordinator()
                    .peekPolicyRuntime()
                    .isReady(),
                "Second maid policy faulted");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                helper.assertTrue(!closeBed.isOwnedBy(f.maid), "Maid stole another maid's occupied head");
                closeBed.claim(other[0]);
                if (f.maid.hasPose(MaidPose.SLEEPING)) {
                    helper.assertTrue(farBed.isOwnedBy(f.maid), "Occupied nearest bed was not excluded from search");
                    helper.setBlock(8, 1, 4, Blocks.air);
                    broken[0] = age;
                    phase[0] = 1;
                }
            } else if (phase[0] == 1 && age > broken[0]) {
                helper.assertFalse(f.maid.hasPose(MaidPose.SLEEPING), "Removed bed left a sleeping maid");
                helper.assertFalse(farBed.isOwnedBy(f.maid), "Removed bed retained its occupant lease");
                var actor = f.core.getActor();
                actor.setWorkTarget(
                    new com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point(
                        closeBed.xCoord,
                        closeBed.yCoord,
                        closeBed.zCoord));
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    actor.sleepEntries() == 1 && !f.maid.hasPose(MaidPose.SLEEPING),
                    "Cancel restarted sleep");
                boolean rejected = false;
                try {
                    actor.sleepAt(
                        new com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point(
                            closeBed.xCoord,
                            closeBed.yCoord,
                            closeBed.zCoord));
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected && closeBed.isOwnedBy(other[0]), "Retired actor changed the foreign bed");
                closeBed.release(other[0]);
                other[0].setPose(MaidPose.SLEEPING, false);
                otherCore[0] = new ModernFollowCoreComponent(other[0]);
                other[0].getBrainCoordinator()
                    .setAfterNavigationActionsForDevelopment(otherCore[0]);
                other[0].getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 2;
            } else if (phase[0] == 2 && other[0].hasPose(MaidPose.SLEEPING)) {
                helper.assertTrue(closeBed.isOwnedBy(other[0]), "Released bed did not become reusable");
                other[0].getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    !occupied(closeBed) && !other[0].hasPose(MaidPose.SLEEPING),
                    "Live sleep cancel leaked bed occupancy");
                done[0] = true;
                System.out.println("TLMNH_LIFE108_BED_CANCEL_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "modern_life_beg")
    public static void mainHandVisibleOwnerBegUsesStrictRangeAndMountedLookOnlyThenCancels(GameTestHelper helper) {
        var f = fixture(helper);
        // 标准Follow夹具只将FakePlayer登记到在线表；可见实体传感器还需要真实区块空间索引
        var ownerChunk = helper.getWorld()
            .getChunkFromBlockCoords((int) f.owner.posX, (int) f.owner.posZ);
        ownerChunk.addEntity(f.owner);
        helper.afterTest(() -> ownerChunk.removeEntity(f.owner));
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setSitting(true);
        String configured = MaidTemptationConfig.itemId;
        helper.afterTest(() -> MaidTemptationConfig.itemId = configured);
        MaidTemptationConfig.itemId = "minecraft:cake";
        f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = new ItemStack(Items.cake);
        int[] phase = { 0 }, changed = { 0 };
        boolean[] done = { false }, approached = { false };
        helper.onEachTick("source visible memory/main hand/range and ride/ground Behavior transitions", () -> {
            ready(helper, f);
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (phase[0] == 0 && age >= 25) {
                helper.assertTrue(
                    f.maid.hasPose(MaidPose.BEGGING) && actor.peekWalkTarget() == null,
                    "RIDE_WORK beg did not set pose/LOOK without WALK");
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = new ItemStack(Items.stick);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age > changed[0]) {
                helper
                    .assertFalse(f.maid.hasPose(MaidPose.BEGGING), "Removed temptation did not stop begging next tick");
                f.owner.setPosition(f.maid.posX + 6, f.maid.posY, f.maid.posZ);
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = new ItemStack(Items.cake);
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age > changed[0] + 2) {
                helper.assertFalse(f.maid.hasPose(MaidPose.BEGGING), "Exact six-block boundary was accepted");
                f.owner.setPosition(f.maid.posX + 5.5, f.maid.posY, f.maid.posZ);
                changed[0] = age;
                phase[0] = 3;
            } else if (phase[0] == 3 && f.maid.hasPose(MaidPose.BEGGING)) {
                helper.assertTrue(actor.peekWalkTarget() == null, "Mounted beg wrote a walk target");
                f.maid.setSitting(false);
                changed[0] = age;
                phase[0] = 4;
            } else if (phase[0] == 4 && age > changed[0] + 25 && f.maid.posX > f.x + 2.5) {
                approached[0] = true;
                helper.assertTrue(f.maid.hasPose(MaidPose.BEGGING), "Ground beg did not keep its active pose");
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = new ItemStack(Items.stick);
                changed[0] = age;
                phase[0] = 5;
            } else if (phase[0] == 5 && age > changed[0]) {
                helper.assertFalse(f.maid.hasPose(MaidPose.BEGGING), "Ground beg did not end after hand change");
                MaidTemptationConfig.itemId = "minecraft:stick";
                changed[0] = age;
                phase[0] = 6;
            } else if (phase[0] == 6 && f.maid.hasPose(MaidPose.BEGGING)) {
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    actor.peekWalkTarget() == null && !f.maid.hasPose(MaidPose.BEGGING),
                    "Cancelled beg retained pose or movement");
                done[0] = true;
                System.out.println("TLMNH_LIFE108_BEG_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> approached[0] && done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 16, 16);
        f.maid.setSchedule(MaidSchedule.NIGHT);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static TileEntityMaidBed bed(GameTestHelper helper, int x, int y, int z) {
        int[] offset = BlockBed.field_149981_a[0];
        helper.setBlock(x - offset[0], y, z - offset[1], MaidLifeContent.maidBed, 0);
        helper.setBlock(x, y, z, MaidLifeContent.maidBed, 8);
        var absolute = helper.absolute(x, y, z);
        var bed = (TileEntityMaidBed) helper.getWorld()
            .getTileEntity(absolute.x(), absolute.y(), absolute.z());
        helper.assertTrue(bed != null && bed.complete(), "Complete real maid bed setup failed");
        return bed;
    }

    private static boolean occupied(TileEntityMaidBed bed) {
        return (bed.getWorldObj()
            .getBlockMetadata(bed.xCoord, bed.yCoord, bed.zCoord) & 4) != 0;
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(f.maid.isEntityAlive(), "Life actor died");
        helper.assertTrue(
            f.maid.getBrainCoordinator()
                .peekPolicyRuntime()
                .isReady(),
            "Life actor policy faulted");
        helper.assertTrue(
            f.core.getActor()
                .teleportAttempts() == 0,
            "Unexpected owner teleport replaced life route");
    }
}
