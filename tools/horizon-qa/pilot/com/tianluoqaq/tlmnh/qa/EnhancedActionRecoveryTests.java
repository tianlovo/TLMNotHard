package com.tianluoqaq.tlmnh.qa;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.config.MaidHomeConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 仅设置地形、目标和伤害输入，完整ENHANCED工厂及自然实体tick执行所有动作 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedActionRecoveryTests {

    private EnhancedActionRecoveryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_recovery_heal")
    public static void rangedCombatGivesHealingATurnThenRestoresHandsAndResumesFiniteShots(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.RANGED_ATTACK);
        var bow = new ItemStack(Items.bow);
        var off = new ItemStack(Items.diamond, 3);
        f.maid.setCurrentItemOrArmor(0, bow);
        f.maid.setOffhandItem(off);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        f.maid.getMaidInventory()
            .setInventorySlotContents(1, new ItemStack(Items.apple));
        var target = pig(helper, f, 12.5, 1, 4.5);
        var core = select(helper, f);
        var ownedBow = core.actor()
            .rangedBow();
        helper.afterTest(() -> {
            // 本例在首次发射后结束，清理该发射器自己持有的原箭，避免下一场景遇到已退休的射手
            var ownedArrow = ownedBow.getLastArrow();
            if (ownedArrow != null) ownedArrow.setDead();
        });
        int[] phase = { 0 }, hurtAt = { -1 }, healingAt = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            var lease = f.maid.getItemUseState()
                .peek();
            if (phase[0] == 0) {
                if (lease == null || lease.getKind() != MaidItemUseState.Kind.BOW
                    || f.maid.getItemUseState()
                        .getUsedTicks() < 2)
                    return false;
                helper.assertTrue(f.maid.attackEntityFrom(DamageSource.generic, 8), "Controlled real damage rejected");
                hurtAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (f.maid.getMealController()
                    .isEating()) {
                    helper.assertTrue(
                        age > hurtAt[0] && f.maid.hurtTime == 0
                            && core.selectedKind() == Kind.HEAL
                            && core.actor()
                                .rangedBow()
                                .getShots() == 0
                            && bow.getItemDamage() == 0
                            && f.maid.getMaidInventory()
                                .getStackInSlot(0).stackSize == 8,
                        "Healing bypassed new-hit response or paid for its cancelled partial bow draw");
                    healingAt[0] = age;
                    phase[0] = 2;
                }
                return false;
            }
            if (phase[0] == 2 && f.maid.getMealController()
                .isEating()) {
                helper.assertTrue(core.selectedKind() == Kind.HEAL, "Combat repeatedly interrupted the same heal");
                return false;
            }
            if (core.actor()
                .rangedBow()
                .getShots() == 0) return false;
            helper.assertTrue(
                age > healingAt[0] && !f.maid.getMealController()
                    .isEating()
                    && f.maid.getHeldItem() == bow
                    && f.maid.getOffhandItem() == off
                    && f.maid.getMaidInventory()
                        .getStackInSlot(1) == null
                    && bow.getItemDamage() == 1
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 7
                    && target.isEntityAlive(),
                "Healing failed to consume one food, restore both original hands or resume finite combat");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 380, batch = "enhanced_recovery_beds")
    public static void unreachableNearestBedIsReleasedForAReachableAlternative(GameTestHelper helper) {
        int oldRange = MaidHomeConfig.sleepRange;
        MaidHomeConfig.sleepRange = 12;
        helper.afterTest(() -> MaidHomeConfig.sleepRange = oldRange);
        var f = fixture(helper, MaidTask.IDLE);
        f.maid.setSchedule(MaidSchedule.NIGHT);
        home(helper, f);
        pillar(helper, 2);
        var blocked = bed(helper, 7, 3, 4);
        var reachable = bed(helper, 15, 1, 4);
        var core = select(helper, f);
        boolean[] firstClaimed = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            firstClaimed[0] |= blocked.isOwnedBy(f.maid);
            if (!f.maid.hasPose(MaidPose.SLEEPING)) return false;
            helper.assertTrue(
                firstClaimed[0] && core.life()
                    .abandonedCandidates() == 1
                    && !blocked.isOwnedBy(f.maid)
                    && reachable.isOwnedBy(f.maid)
                    && core.life()
                        .sleepEntries() == 1
                    && (helper.getWorld()
                        .getBlockMetadata(blocked.xCoord, blocked.yCoord, blocked.zCoord) & 4) == 0,
                "Nearest failed bed was reacquired forever, leaked its reservation or bypassed the alternative");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 340, batch = "enhanced_recovery_snacks")
    public static void unreachableSnackCabinetDoesNotStarveAReachableCake(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.IDLE);
        home(helper, f);
        pillar(helper, 2);
        helper.setBlock(7, 3, 4, MaidLifeContent.snackCabinet);
        helper.setBlock(7, 4, 4, Blocks.cake);
        helper.setBlock(12, 0, 4, MaidLifeContent.snackCabinet);
        helper.setBlock(12, 1, 4, Blocks.cake);
        var main = new ItemStack(Items.stick, 4);
        var off = new ItemStack(Items.diamond, 3);
        f.maid.setCurrentItemOrArmor(0, main);
        f.maid.setOffhandItem(off);
        var core = select(helper, f);
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            if (core.life()
                .bites() == 0) return false;
            var first = helper.absolute(7, 4, 4);
            var second = helper.absolute(12, 1, 4);
            helper.assertTrue(
                core.life()
                    .abandonedCandidates() == 1
                    && core.life()
                        .bites() == 1
                    && helper.getWorld()
                        .getBlockMetadata(first.x(), first.y(), first.z()) == 0
                    && helper.getWorld()
                        .getBlockMetadata(second.x(), second.y(), second.z()) == 1
                    && f.maid.getHeldItem() == main
                    && f.maid.getOffhandItem() == off,
                "Failed snack approach never yielded, consumed the inaccessible cake or changed hand data");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 400, batch = "enhanced_recovery_targets")
    public static void unreachableThreatYieldsToAnotherAndMovementMakesTheOriginalEligibleAgain(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.ATTACK);
        // 平面隔岛保持目标可见；旧高柱同时遮挡两个候选，未必进入追击失败路径
        for (int x = 5; x <= 9; x++)
            for (int z = 2; z <= 6; z++) if (x != 7 || z != 4) helper.setBlock(x, 0, z, Blocks.air);
        var blocked = pig(helper, f, 7.5, 1, 4.5);
        var reachable = pig(helper, f, 11.5, 1, 1.5);
        // 两条路线均留在主人的跟随起步距离内，避免合法的FOLLOW指令抢占被测战斗
        f.owner.setPosition(f.x + 6.5, f.y + 1, f.z + 1.5);
        helper.assertTrue(
            f.maid.canEntityBeSeen(blocked) && f.maid.canEntityBeSeen(reachable)
                && f.maid.getDistanceSqToEntity(blocked) < f.maid.getDistanceSqToEntity(reachable),
            "Both real threats must start visible, with the isolated target nearer");
        var core = select(helper, f);
        boolean[] selectedBlocked = { false }, moved = { false };
        int[] abandonedAt = { -1 };
        String[] previous = { "" };
        helper.onEachTick("unreachable threat natural decision state", () -> {
            var target = core.combat()
                .target();
            String key = (target == blocked ? "blocked" : target == reachable ? "reachable" : "none") + ":"
                + core.combat()
                    .abandonedTargets()
                + ":"
                + moved[0];
            if (!key.equals(previous[0]) || f.maid.ticksExisted == 200 || f.maid.ticksExisted == 399) {
                previous[0] = key;
                System.out.println("TLMNH_UNREACHABLE_THREAT_STATE " + threatState(f, core, blocked, reachable));
            }
        });
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            selectedBlocked[0] |= core.combat()
                .target() == blocked;
            if (core.combat()
                .abandonedTargets() > 0 && abandonedAt[0] < 0) abandonedAt[0] = f.maid.ticksExisted;
            if (!moved[0]) {
                if (reachable.getHealth() == 200) return false;
                helper.assertTrue(
                    selectedBlocked[0] && core.combat()
                        .abandonedTargets() == 1 && blocked.getHealth() == 200 && abandonedAt[0] >= 0,
                    "Unreachable target was not tried/released before attacking the alternate threat: "
                        + threatState(f, core, blocked, reachable));
                reachable.setDead();
                blocked.setPosition(f.maid.posX + 1.5, f.y + 1, f.maid.posZ);
                blocked.motionX = blocked.motionY = blocked.motionZ = 0;
                moved[0] = true;
                return false;
            }
            if (blocked.getHealth() == 200) return false;
            helper.assertTrue(
                f.maid.ticksExisted < abandonedAt[0] + 100 && core.combat()
                    .abandonedTargets() == 1,
                "Moving the actual target did not invalidate its old failed-position cooldown: "
                    + threatState(f, core, blocked, reachable));
            return true;
        });
    }

    private static String threatState(ModernCoreTests.Fixture f, EnhancedCoreBrain core, EntityPig blocked,
        EntityPig reachable) {
        var path = f.navigation.getPath();
        return "age=" + f.maid.ticksExisted
            + ", selected="
            + core.selectedKind()
            + ", target="
            + (core.combat()
                .target() == blocked ? "blocked"
                    : core.combat()
                        .target() == reachable ? "reachable" : "none")
            + ", abandoned="
            + core.combat()
                .abandonedTargets()
            + ", cantReachSince="
            + core.actor()
                .peekCantReachSince()
            + ", navigation="
            + core.navigation()
                .outcome()
            + ", noPath="
            + f.navigation.noPath()
            + ", pathCanReach="
            + (path instanceof com.tianluoqaq.tlmnh.common.ai.navigation.MaidModernPath modern ? modern.canReach()
                : null)
            + ", maid="
            + f.maid.posX
            + "/"
            + f.maid.posY
            + "/"
            + f.maid.posZ
            + ", ownerDistance="
            + f.maid.getDistanceToEntity(f.owner)
            + ", blockedHealth="
            + blocked.getHealth()
            + ", reachableHealth="
            + reachable.getHealth()
            + ", blockedVisible="
            + f.maid.canEntityBeSeen(blocked)
            + ", reachableVisible="
            + f.maid.canEntityBeSeen(reachable);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, MaidTask task) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setTask(task);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static void home(GameTestHelper helper, ModernCoreTests.Fixture f) {
        f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
        helper.assertTrue(
            f.maid.getHomeArea()
                .setEnabled(true),
            "Controlled home setup failed");
        f.maid.setPosition(f.x + 1.5, f.y + 1, f.z + 4.5);
    }

    private static void pillar(GameTestHelper helper, int height) {
        for (int x = 6; x <= 8; x++)
            for (int z = 3; z <= 6; z++) for (int y = 1; y <= height; y++) helper.setBlock(x, y, z, Blocks.stone);
    }

    private static TileEntityMaidBed bed(GameTestHelper helper, int x, int y, int z) {
        int[] offset = BlockBed.field_149981_a[0];
        helper.setBlock(x - offset[0], y, z - offset[1], MaidLifeContent.maidBed, 0);
        helper.setBlock(x, y, z, MaidLifeContent.maidBed, 8);
        var p = helper.absolute(x, y, z);
        var bed = (TileEntityMaidBed) helper.getWorld()
            .getTileEntity(p.x(), p.y(), p.z());
        helper.assertTrue(bed != null && bed.complete(), "Actual two-part bed setup failed");
        return bed;
    }

    private static EntityPig pig(GameTestHelper helper, ModernCoreTests.Fixture f, double x, double y, double z) {
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        helper.spawnEntity(pig, x, y, z);
        helper.afterTest(pig::setDead);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        return pig;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Full enhanced factory selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && brain.peekEnhancedCore() == core
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Recovery scenario lost its actual full N root or entered prototype execution");
    }
}
