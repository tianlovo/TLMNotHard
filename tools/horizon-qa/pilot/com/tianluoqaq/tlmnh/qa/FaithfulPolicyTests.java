package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class FaithfulPolicyTests {

    private FaithfulPolicyTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "faithful_policy_lifecycle")
    public static void unavailableSelectionAndNativeReadCannotReuseFaithfulAuthority(GameTestHelper helper) {
        helper.assertTrue(Boolean.getBoolean("tlmnh.qaFaithfulProfile"), "Canonical F fixture selection required");
        var f = new ModernCoreTests.Fixture(helper, false);
        var brain = f.maid.getBrainCoordinator();
        var apple = new ItemStack(Items.golden_apple, 3, 1);
        apple.setStackDisplayName("F lifecycle original");
        apple.getTagCompound()
            .setString("OpaqueData", "retain");
        f.maid.setOffhandItem(apple.copy());
        f.maid.getMaidInventory()
            .setInventorySlotContents(2, new ItemStack(Items.diamond, 7));
        var old = new MaidPolicyRuntime[2];
        var oldBrain = new MaidFaithfulBrain[2];
        int[] phase = { 0 };
        long[] legacy = new long[2];
        helper.succeedWhen(() -> {
            int age = f.maid.ticksExisted;
            if (age < 4) return false;
            if (phase[0] == 0) {
                old[0] = brain.peekPolicyRuntime();
                oldBrain[0] = brain.peekFaithfulBrain();
                helper.assertTrue(
                    oldBrain[0] != null && old[0].getProfile() == MaidBrainProfile.FAITHFUL_TLM,
                    "Expected actual canonical F before mode transition");
                var token = old[0].getContext()
                    .getToken();
                legacy[0] = brain.getCombatController()
                    .getTicks();
                legacy[1] = brain.getFollowController()
                    .getTicks();
                MinecraftServer.getServer()
                    .getCommandManager()
                    .executeCommand(
                        MinecraftServer.getServer(),
                        "tlmnhdev brain-profile " + f.maid.getEntityId() + " enhanced");
                helper.assertTrue(
                    old[0].isRetired() && !old[0].getContext()
                        .isCurrent(token),
                    "Command selection retained the old F authority");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                        && !brain.peekPolicyRuntime()
                            .isReady()
                        && brain.peekFaithfulBrain() == null,
                    "Unavailable N silently restored F or prototype");
                helper.assertTrue(
                    legacy[0] == brain.getCombatController()
                        .getTicks() && legacy[1]
                            == brain.getFollowController()
                                .getTicks(),
                    "Unavailable N dispatched prototype");
                if (age < 8) return false;
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                        == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Explicit F restoration failed");
                old[1] = brain.peekPolicyRuntime();
                oldBrain[1] = brain.peekFaithfulBrain();
                helper
                    .assertTrue(old[1] != old[0] && oldBrain[1] != oldBrain[0], "F reused a retired runtime or Brain");
                var tag = new NBTTagCompound();
                f.maid.writeToNBT(tag);
                f.maid.readFromNBT(tag);
                helper.assertTrue(
                    old[1].isRetired() && brain.peekFaithfulBrain() == null,
                    "Native NBT read retained old F behavior state");
                phase[0] = 2;
                return false;
            }
            if (age < 13) return false;
            helper.assertTrue(
                brain.peekPolicyRuntime() != old[1] && brain.peekPolicyRuntime()
                    .isReady()
                    && brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.FAITHFUL_TLM
                    && brain.peekFaithfulBrain() != null
                    && brain.peekFaithfulBrain() != oldBrain[1],
                "Natural tick did not rebuild canonical F after native read");
            helper.assertTrue(
                ItemStack.areItemStacksEqual(apple, f.maid.getOffhandItem()) && f.maid.getMaidInventory()
                    .getStackInSlot(2).stackSize == 7
                    && f.maid.getMaidInventory()
                        .recovery()
                        .size() == 0,
                "Mode/NBT boundary lost or duplicated items");
            System.out.println(
                "TLMNH_F_POLICY_LIFECYCLE_PASS ticks=" + age + " unavailable=no_fallback nbt=fresh_F items=retained");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 20, batch = "faithful_policy_failed_cleanup")
    public static void failedOldCleanupCannotConstructOrResumeTheRequestedFaithfulBrain(GameTestHelper helper) {
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
            if (age < 4) return false;
            if (!attempted[0]) {
                actions.armed = true;
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM)
                        == MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE,
                    "Failed cleanup admitted new F");
                attempted[0] = true;
                return false;
            }
            if (age < 9) return false;
            helper.assertTrue(
                old.isRetired() && brain.peekFaithfulBrain() == null && actions.calls == 1,
                "Failed old cleanup was retried or an F factory resumed");
            var fault = IntegrationGuard.faults(Side.SERVER)
                .get(ExternalFeature.BEHAVIOR_RUNTIME.id + "/" + f.maid.getUniqueID());
            helper.assertTrue(
                fault != null && fault.cause == actions.failure,
                "Profile switch escaped per-maid failure protection");
            helper.assertTrue(
                f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.LEGACY_PROTOTYPE)
                    == MaidPolicyRuntime.SelectionResult.REFUSED_LIFECYCLE && actions.calls == 1,
                "Ordinary selection bypassed the stopped behavior boundary");
            helper.assertTrue(
                ItemStack.areItemStacksEqual(apple, f.maid.getOffhandItem()) && f.maid.isEntityAlive(),
                "Failed selection changed owned item or killed maid");
            System.out
                .println("TLMNH_F_POLICY_CLEANUP_PASS ticks=" + age + " cleanup=1 factory=blocked items=retained");
            return true;
        });
    }

    private static final class FailingCleanup implements MaidPolicyRuntime.AfterNavigationActions {

        final NoSuchMethodError failure = new NoSuchMethodError("QA old profile cleanup API removed");
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
