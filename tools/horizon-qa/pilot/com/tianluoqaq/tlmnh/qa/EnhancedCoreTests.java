package com.tianluoqaq.tlmnh.qa;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedCoreTests {

    private EnhancedCoreTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 90, batch = "enhanced_core_priority")
    public static void liveCommandPreemptsPickupAndDeadTargetCannotUseHysteresis(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        var core = select(helper, f);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
        f.maid.setPickup(true);
        var item = new EntityItem(f.maid.worldObj, f.x + 7.5, f.y + 1, f.z + 6.5, new ItemStack(Items.diamond, 2));
        item.delayBeforeCanPickup = 0;
        helper.assertTrue(f.maid.worldObj.spawnEntityInWorld(item), "Pickup competitor did not spawn");
        helper.afterTest(item::setDead);
        int[] phase = { 0 }, changedAt = { 0 };
        helper.succeedWhen(() -> {
            assertIndependent(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                if (core.selectedKind() != Kind.PICKUP) return false;
                helper.assertTrue(item.isEntityAlive(), "Observed pickup after target was gone");
                f.owner.setPosition(f.x + 15.5, f.y + 1, f.z + 4.5);
                changedAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (age <= changedAt[0]) return false;
                helper.assertTrue(core.selectedKind() == Kind.FOLLOW, "Command did not preempt legal pickup next tick");
                helper.assertTrue(
                    core.actor()
                        .peekWalkTarget() != null && f.navigation.searchCount() >= 2,
                    "Independent root did not replace pickup navigation with live owner target");
                item.setDead();
                f.owner.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ);
                changedAt[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age <= changedAt[0]) return false;
            helper.assertTrue(
                core.selectedKind() == null && core.actor()
                    .peekWalkTarget() == null && f.navigation.noPath(),
                "Dead pickup was retained by hysteresis or old route survived cancellation");
            return age >= changedAt[0] + 5;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 35, batch = "enhanced_core_air")
    public static void lowAirPreemptsHealingAndReturnsBothUnconsumedStacks(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, true);
        var core = select(helper, f);
        f.owner.setPosition(f.x + 2.5, f.y + 1, f.z + 4.5);
        f.maid.setHealth(10);
        var food = new ItemStack(Items.golden_apple, 3, 1);
        var potion = new ItemStack(Items.potionitem, 1, 8237);
        helper.assertTrue(
            Items.potionitem.getEffects(potion)
                .stream()
                .anyMatch(
                    value -> ((net.minecraft.potion.PotionEffect) value).getPotionID()
                        == net.minecraft.potion.Potion.waterBreathing.id),
            "Native breathing potion metadata differs");
        var hand = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.diamond, 7);
        f.maid.setCurrentItemOrArmor(0, hand.copy());
        f.maid.setOffhandItem(off.copy());
        f.maid.getMaidInventory()
            .setInventorySlotContents(2, food.copy());
        int[] phase = { 0 }, changedAt = { 0 };
        helper.succeedWhen(() -> {
            assertIndependent(helper, f, core);
            int age = f.maid.ticksExisted;
            if (age < 3) return false;
            if (phase[0] == 0) {
                helper.assertTrue(
                    core.selectedKind() == Kind.HEAL && f.maid.getMealController()
                        .isEating(),
                    "Independent care branch did not start actual hand transaction");
                f.maid.getMaidInventory()
                    .setInventorySlotContents(3, potion.copy());
                f.maid.setAir(60);
                changedAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changedAt[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.selectedKind() == Kind.BREATHE && f.maid.getMealController()
                        .isBreathing(),
                    "Low air failed to preempt active healing with actual breathing item");
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(
                        food,
                        f.maid.getMaidInventory()
                            .getStackInSlot(2)),
                    "Interrupted healing consumed or lost original food");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(
                        potion,
                        f.maid.getMaidInventory()
                            .getStackInSlot(3))
                        && ItemStack.areItemStacksEqual(hand, f.maid.getHeldItem())
                        && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem()),
                    "Suspension failed to return both unconsumed stacks and original hands");
                changedAt[0] = age;
                phase[0] = 2;
                return false;
            }
            helper.assertTrue(
                !f.maid.getMealController()
                    .isEating()
                    && f.maid.getItemUseState()
                        .peek() == null,
                "Suspended enhanced component restarted item use");
            return age >= changedAt[0] + 4;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "enhanced_core_lifecycle")
    public static void nativeReadRebuildsIndependentCoreAndFullEnhancedRemainsUnavailable(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        var first = select(helper, f);
        var brain = f.maid.getBrainCoordinator();
        var old = brain.peekPolicyRuntime();
        var token = old.getContext()
            .getToken();
        var diamond = new ItemStack(Items.diamond, 7);
        f.maid.setOffhandItem(diamond.copy());
        int[] phase = { 0 }, changedAt = { 0 };
        long[] legacy = new long[2];
        helper.succeedWhen(() -> {
            int age = f.maid.ticksExisted;
            if (age < 4) return false;
            if (phase[0] == 0) {
                assertIndependent(helper, f, first);
                var tag = new NBTTagCompound();
                f.maid.writeToNBT(tag);
                f.maid.readFromNBT(tag);
                helper.assertTrue(
                    old.isRetired() && !old.getContext()
                        .isCurrent(token),
                    "Native read kept old N component authority");
                changedAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changedAt[0]) return false;
            if (phase[0] == 1) {
                var second = brain.peekEnhancedCore();
                helper.assertTrue(
                    second != null && second != first
                        && brain.peekPolicyRuntime() != old
                        && brain.peekPolicyRuntime()
                            .getProfile() == MaidBrainProfile.ENHANCED_CORE_COMPONENT,
                    "Natural tick failed to reconstruct independent core after native read");
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                        == MaidPolicyRuntime.SelectionResult.UNAVAILABLE,
                    "Partial core acquired complete N identity");
                f.maid.setHealth(10);
                f.maid.getMaidInventory()
                    .setInventorySlotContents(1, new ItemStack(Items.golden_apple, 2, 1));
                legacy[0] = brain.getFollowController()
                    .getTicks();
                legacy[1] = brain.getCombatController()
                    .getTicks();
                changedAt[0] = age;
                phase[0] = 2;
                return false;
            }
            helper.assertTrue(
                !brain.peekPolicyRuntime()
                    .isReady() && brain.peekEnhancedCore() == null
                    && brain.peekFaithfulBrain() == null
                    && legacy[0] == brain.getFollowController()
                        .getTicks()
                    && legacy[1] == brain.getCombatController()
                        .getTicks(),
                "Unavailable full N dispatched another decision root");
            helper.assertTrue(
                !f.maid.getMealController()
                    .isEating()
                    && f.maid.getItemUseState()
                        .peek() == null
                    && ItemStack.areItemStacksEqual(
                        new ItemStack(Items.golden_apple, 2, 1),
                        f.maid.getMaidInventory()
                            .getStackInSlot(1)),
                "Unavailable full N restarted prototype meal outside its decision root");
            helper.assertTrue(
                ItemStack.areItemStacksEqual(diamond, f.maid.getOffhandItem()),
                "Lifecycle lost owned item");
            return age >= changedAt[0] + 5;
        });
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Independent enhanced core selection failed");
        var result = f.maid.getBrainCoordinator()
            .peekEnhancedCore();
        helper.assertTrue(
            result != null && f.maid.getBrainCoordinator()
                .peekFaithfulBrain() == null,
            "Enhanced selection reused F");
        return result;
    }

    private static void assertIndependent(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            brain.peekEnhancedCore() == core && brain.peekFaithfulBrain() == null
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED_CORE_COMPONENT
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0
                && !brain.getBreathController()
                    .isActive(),
            "Enhanced root fell back to F or legacy controllers");
    }
}
