package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.MaidWorldNavigationBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.config.MaidNavigationConfig;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedPolicyTests {

    private EnhancedPolicyTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 25, batch = "enhanced_policy_air_budget")
    public static void completeModeRunsItsOwnNaturalRootAndKeepsAirSearchInsideWorldQuota(GameTestHelper helper) {
        int previous = MaidNavigationConfig.queriesPerTick;
        MaidNavigationConfig.queriesPerTick = 1;
        MaidWorldNavigationBudget.unload(helper.getWorld());
        helper.afterTest(() -> {
            MaidNavigationConfig.queriesPerTick = previous;
            MaidWorldNavigationBudget.unload(helper.getWorld());
        });
        var f = new ModernCoreTests.Fixture(helper, true);
        f.owner.setPosition(f.x + 11.5, f.y + 11, f.z + 4.5);
        var root = select(helper, f);
        f.maid.setAir(60);
        var budget = MaidWorldNavigationBudget.forWorld(helper.getWorld());
        long[] searches = { 0 };
        helper.succeedWhen(() -> {
            identity(helper, f, root);
            long current = f.navigation.searchCount();
            helper.assertTrue(
                current - searches[0] <= 1 && budget.used(
                    helper.getWorld()
                        .getTotalWorldTime())
                    <= 1,
                "Complete enhanced mode bypassed world A* quota");
            searches[0] = current;
            if (f.maid.ticksExisted < 6) return false;
            helper.assertTrue(
                root.decisions() > 0 && root.selectedKind() == Kind.BREATHE,
                "Natural native phase did not run the actual complete N root");
            helper.assertTrue(
                current > 0 && f.navigation.budgetDeferrals() > 0 && budget.queued() == 1,
                "Full N air preflight did not retain its actual deferred search");
            helper.assertTrue(
                root.follow()
                    .attempts() == 0
                    && root.navigation()
                        .failureCount() == 0
                    && f.maid.getHealth() == 20,
                "Deferred search caused false recovery, failure or damage");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 75, batch = "enhanced_policy_lifecycle")
    public static void movingRootSuspensionNativeReadAndFaithfulRoundTripRetireOldAuthority(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        var brain = f.maid.getBrainCoordinator();
        var first = select(helper, f);
        var old = brain.peekPolicyRuntime();
        var hand = new ItemStack(Items.stick, 5);
        var off = new ItemStack(Items.diamond, 7);
        off.setStackDisplayName("N lifecycle original");
        off.getTagCompound()
            .setString("UnknownPayload", "retain");
        f.maid.setCurrentItemOrArmor(0, hand.copy());
        f.maid.setOffhandItem(off.copy());
        double initialX = f.maid.posX;
        int[] phase = { 0 }, changedAt = { 0 };
        long[] decisions = { 0 };
        EnhancedCoreBrain[] second = { null }, third = { null };
        MaidPolicyRuntime[] intermediate = { null };
        helper.succeedWhen(() -> {
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                ItemStack.areItemStacksEqual(hand, f.maid.getHeldItem())
                    && ItemStack.areItemStacksEqual(off, f.maid.getOffhandItem())
                    && f.maid.getHealth() == 20,
                "Profile lifecycle changed held NBT or health");
            if (phase[0] == 0) {
                identity(helper, f, first);
                if (f.navigation.searchCount() == 0 || f.maid.posX <= initialX + .2) return false;
                var token = old.getContext()
                    .getToken();
                brain.setSuspended(true);
                helper.assertTrue(
                    !old.getContext()
                        .isCurrent(token)
                        && !first.actor()
                            .isCurrent(),
                    "Suspension kept the old token or actor authority");
                decisions[0] = first.decisions();
                changedAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(first.decisions() == decisions[0], "Suspended N still dispatched decisions");
                if (age < changedAt[0] + 3) return false;
                var tag = new NBTTagCompound();
                f.maid.writeToNBT(tag);
                f.maid.readFromNBT(tag);
                helper.assertTrue(
                    old.isRetired() && brain.peekEnhancedCore() == null,
                    "Native NBT retained the old enhanced root");
                brain.setSuspended(false);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                second[0] = brain.peekEnhancedCore();
                if (second[0] == null || second[0].decisions() == 0) return false;
                identity(helper, f, second[0]);
                helper.assertTrue(
                    second[0] != first && brain.peekPolicyRuntime() != old
                        && !first.actor()
                            .isCurrent(),
                    "Natural NBT rebuild reused retired policy, root or actor");
                intermediate[0] = brain.peekPolicyRuntime();
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                        == MaidPolicyRuntime.SelectionResult.SELECTED && intermediate[0].isRetired()
                        && brain.peekFaithfulBrain() != null
                        && brain.peekEnhancedCore() == null,
                    "Explicit F transition retained N or reused a generic callback");
                changedAt[0] = age;
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                if (age < changedAt[0] + 3) return false;
                helper.assertTrue(
                    brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.FAITHFUL_TLM && brain.peekFaithfulBrain() != null,
                    "Natural tick did not retain actual F identity");
                third[0] = select(helper, f);
                helper.assertTrue(third[0] != first && third[0] != second[0], "Full N round trip reused old root");
                changedAt[0] = age;
                phase[0] = 4;
                return false;
            }
            identity(helper, f, third[0]);
            return age >= changedAt[0] + 3 && third[0].decisions() > 0;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "enhanced_policy_cleanup_failure")
    public static void cleanupFailureBlocksCompleteModeFactoryWithoutLosingTheOriginalApple(GameTestHelper helper) {
        IntegrationGuard.resetForDevelopment(Side.SERVER);
        helper.afterTest(() -> IntegrationGuard.resetForDevelopment(Side.SERVER));
        var f = new ModernCoreTests.Fixture(helper, false);
        var brain = f.maid.getBrainCoordinator();
        var actions = new FailingCleanup();
        brain.setAfterNavigationActionsForDevelopment(actions);
        var old = brain.peekPolicyRuntime();
        var apple = new ItemStack(Items.golden_apple, 3, 1);
        f.maid.setOffhandItem(apple.copy());
        boolean[] attempted = { false };
        helper.succeedWhen(() -> {
            int age = f.maid.ticksExisted;
            if (age < 3) return false;
            if (!attempted[0]) {
                actions.armed = true;
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                        == MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE,
                    "Failed cleanup admitted full N");
                attempted[0] = true;
                return false;
            }
            if (age < 8) return false;
            var fault = IntegrationGuard.faults(Side.SERVER)
                .get(ExternalFeature.BEHAVIOR_RUNTIME.id + "/" + f.maid.getUniqueID());
            helper.assertTrue(
                old.isRetired() && brain.peekEnhancedCore() == null
                    && brain.peekFaithfulBrain() == null
                    && actions.calls == 1
                    && fault != null
                    && fault.cause == actions.failure,
                "Failed cleanup retried or constructed a new decision root");
            helper.assertTrue(
                f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.LEGACY_PROTOTYPE)
                    == MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE && actions.calls == 1,
                "Explicit selection bypassed protected lifecycle");
            helper.assertTrue(
                ItemStack.areItemStacksEqual(apple, f.maid.getOffhandItem()) && f.maid.isEntityAlive(),
                "Failure fallback changed original item or killed maid");
            return true;
        });
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Complete N factory selection failed");
        var root = f.maid.getBrainCoordinator()
            .peekEnhancedCore();
        identity(helper, f, root);
        return root;
    }

    private static void identity(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain root) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            root != null && root instanceof MaidPolicyRuntime.EnhancedActions
                && brain.peekEnhancedCore() == root
                && brain.peekFaithfulBrain() == null
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekPolicyRuntime()
                    .requiresAfterNativeNavigation()
                && brain.getCombatController()
                    .getTicks() == 0
                && brain.getFollowController()
                    .getTicks() == 0,
            "Complete N silently used component identity, F or legacy controllers");
    }

    private static final class FailingCleanup implements MaidPolicyRuntime.AfterNavigationActions {

        final NoSuchMethodError failure = new NoSuchMethodError("QA old policy cleanup API removed");
        boolean armed;
        int calls;

        @Override
        public void tick(MaidPolicyContext.Token token) {}

        @Override
        public void cancel() {
            if (armed) {
                calls++;
                throw failure;
            }
        }
    }
}
