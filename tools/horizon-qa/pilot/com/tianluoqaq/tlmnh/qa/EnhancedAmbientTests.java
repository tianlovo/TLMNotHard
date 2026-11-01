package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.perception.MaidSensingBudget;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedAmbientTests {

    private EnhancedAmbientTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_ambient_beg")
    public static void visibleFoodRequestsNeverTakeItemsAndHiddenOwnerOrCommandRetiresBeg(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        var core = select(helper, f);
        var cake = new ItemStack(Items.cake, 3);
        cake.setStackDisplayName("QA-owner-food");
        var main = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.diamond, 7);
        f.maid.setCurrentItemOrArmor(0, main.copy());
        f.maid.setOffhandItem(off.copy());
        f.owner.inventory.mainInventory[0] = cake.copy();
        f.owner.inventory.currentItem = 0;
        f.owner.setPosition(f.x + 5.5, f.y + 1, f.z + 4.5);
        int[] phase = { 0 }, changed = { 0 };
        helper.succeedWhen(() -> {
            check(helper, f, core);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(cake, f.owner.getHeldItem())
                    && ItemStack.areItemStacksEqual(main, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                "Request moved or consumed actual items");
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.selectedKind() != Kind.BEG || !f.maid.hasPose(MaidPose.BEGGING)
                    || f.navigation.searchCount() == 0) return false;
                wall(helper, 4, true);
                helper.assertTrue(!f.maid.canEntityBeSeen(f.owner), "Actual wall did not hide the owner");
                phase[0] = 1;
                changed[0] = age;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() != Kind.BEG && !f.maid.hasPose(MaidPose.BEGGING),
                    "Cached owner visibility kept the request active");
                wall(helper, 4, false);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (core.selectedKind() != Kind.BEG || !f.maid.hasPose(MaidPose.BEGGING)) return false;
                f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 4.5);
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                helper.assertTrue(
                    core.selectedKind() == Kind.FOLLOW && !f.maid.hasPose(MaidPose.BEGGING),
                    "Live follow command did not preempt the low-priority request");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    f.navigation.noPath() && core.actor()
                        .peekWalkTarget() == null
                        && !core.sensing()
                            .queued(MaidSensingBudget.Kind.AMBIENT),
                    "Suspension retained an ambient request or route");
                changed[0] = age;
                phase[0] = 4;
                return false;
            }
            helper.assertTrue(
                !f.maid.hasPose(MaidPose.BEGGING) && f.navigation.noPath(),
                "Suspended root resumed a low-priority action");
            return age >= changed[0] + 4;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "enhanced_ambient_walk")
    public static void glassWanderMovesNaturallyThenLostSupportCancelsBeforeCooldownAndResume(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.glass);
        f.maid.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
        f.previousX = f.maid.posX;
        f.previousY = f.maid.posY;
        f.previousZ = f.maid.posZ;
        f.owner.setPosition(f.x + 80.5, f.y + 1, f.z + 4.5);
        f.owner.setDead();
        var core = select(helper, f);
        int[] phase = { 0 }, changed = { 0 };
        long[] samples = { 0 }, searches = { 0 };
        var lost = new com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point[1];
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                var point = core.ambient()
                    .destination();
                double dx = f.maid.posX - (f.x + 10.5), dz = f.maid.posZ - (f.z + 4.5);
                if (core.selectedKind() != Kind.WANDER || point == null
                    || f.navigation.noPath()
                    || dx * dx + dz * dz < .04
                    || f.maid.getDistanceSq(point.x + .5, point.y, point.z + .5) < 4) return false;
                helper.assertTrue(
                    f.maid.worldObj.getBlock(point.x, point.y - 1, point.z) == Blocks.glass,
                    "Actual wander did not use collision-supported glass");
                lost[0] = point;
                f.maid.worldObj.setBlockToAir(point.x, point.y - 1, point.z);
                changed[0] = age;
                samples[0] = core.ambient()
                    .samples();
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.ambient()
                        .destination() == null && core.selectedKind() != Kind.WANDER
                        && f.navigation.noPath()
                        && core.actor()
                            .peekWalkTarget() == null,
                    "Lost destination support retained a stale plan or actual route");
                helper.assertTrue(
                    core.ambient()
                        .samples() == samples[0],
                    "Invalid landing caused per-tick resampling");
                if (age < changed[0] + 5) return false;
                f.maid.worldObj.setBlock(lost[0].x, lost[0].y - 1, lost[0].z, Blocks.glass);
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    !core.sensing()
                        .queued(MaidSensingBudget.Kind.AMBIENT),
                    "Cancelled ambient scope stayed in the shared scan queue");
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                helper.assertTrue(f.navigation.noPath(), "Suspended wander continued moving");
                if (age < changed[0] + 4) return false;
                searches[0] = f.navigation.searchCount();
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 3;
                return false;
            }
            return core.selectedKind() == Kind.WANDER && core.ambient()
                .destination() != null && f.navigation.searchCount() > searches[0] && !f.navigation.noPath();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "enhanced_ambient_look")
    public static void naturalObservationRejectsActualOcclusionAndPreservesAnotherHandLease(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        f.owner.setPosition(f.x + 80.5, f.y + 1, f.z + 4.5);
        f.owner.setDead();
        var wolf = new EntityWolf(f.maid.worldObj);
        wolf.setTamed(true);
        wolf.func_152115_b(
            f.owner.getUniqueID()
                .toString());
        wolf.setSitting(true);
        helper.spawnEntity(wolf, 5.5, 1, 4.5);
        helper.afterTest(wolf::setDead);
        var core = select(helper, f);
        int[] phase = { 0 }, changed = { 0 }, wallX = { 0 };
        var lease = new com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Lease[1];
        helper.afterTest(
            () -> f.maid.getItemUseState()
                .release(lease[0]));
        helper.succeedWhen(() -> {
            check(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.selectedKind() != Kind.OBSERVE || core.ambient()
                    .watching() != wolf) return false;
                int direction = f.maid.posX - f.x < 10 ? 1 : -1;
                wolf.setPosition(f.maid.posX + direction * 4, f.maid.posY, f.maid.posZ);
                wallX[0] = (int) Math.floor(f.maid.posX - f.x + direction * 2);
                wall(helper, wallX[0], true);
                helper.assertTrue(!f.maid.canEntityBeSeen(wolf), "Actual wall did not hide the observed actor");
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.ambient()
                        .watching() == null && core.selectedKind() != Kind.OBSERVE
                        && (!core.actor()
                            .isCurrent()
                            || !core.actor()
                                .hasLookTarget()),
                    "Cached visibility retained the hidden ambient entity or LOOK");
                wolf.setDead();
                wall(helper, wallX[0], false);
                var state = f.maid.getItemUseState();
                lease[0] = state.tryAcquire(
                    new Object(),
                    new Object(),
                    com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Hand.MAIN,
                    com.tianluoqaq.tlmnh.common.combat.MaidItemUseState.Kind.FOOD,
                    () -> true);
                helper.assertTrue(lease[0] != null, "Foreign hand lease was not established");
                phase[0] = 2;
                changed[0] = age;
                return false;
            }
            helper.assertTrue(
                f.maid.getItemUseState()
                    .peek() == lease[0] && core.selectedKind() == null
                    && core.ambient()
                        .watching() == null
                    && core.ambient()
                        .destination() == null,
                "Ambient action overwrote another hand capability or reused the dead entity");
            return age >= changed[0] + 4;
        });
    }

    private static void wall(GameTestHelper helper, int x, boolean blocked) {
        for (int y = 1; y <= 4; y++)
            for (int z = 1; z < 8; z++) helper.setBlock(x, y, z, blocked ? Blocks.stone : Blocks.air);
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
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            brain.peekEnhancedCore() == core && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Ambient activity delegated decisions to F or legacy root");
    }
}
