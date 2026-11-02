package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulFlightProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class FaithfulFlightFactoryTests {

    private FaithfulFlightFactoryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "faithful_flight_factory")
    public static void fullFAndSamePhysicsControlRetainTheirFactoryContractAcrossNativeRead(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false);
        var brain = f.maid.getBrainCoordinator();
        var item = new ItemStack(Items.golden_apple, 3, 1);
        item.setStackDisplayName("Factory contract original");
        item.getTagCompound()
            .setString("OpaqueData", "retained");
        f.maid.setOffhandItem(item.copy());
        var previous = new MaidFaithfulBrain[1];
        int[] phase = { 0 }, next = { 4 };
        helper.succeedWhen(() -> {
            if (f.maid.ticksExisted < next[0]) return false;
            if (phase[0] == 0) {
                var selected = f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM);
                helper.assertTrue(
                    selected == MaidPolicyRuntime.SelectionResult.SELECTED
                        || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED,
                    "Full F selection failed");
                assertContract(helper, brain.peekFaithfulBrain(), MaidFaithfulFlightProfile.SOURCE_ORIGINAL);
                previous[0] = brain.peekFaithfulBrain();
                nativeRead(f.maid);
                helper.assertTrue(brain.peekFaithfulBrain() == null, "NBT read retained the old root");
            } else if (phase[0] == 1) {
                assertContract(helper, brain.peekFaithfulBrain(), MaidFaithfulFlightProfile.SOURCE_ORIGINAL);
                helper.assertTrue(previous[0] != brain.peekFaithfulBrain(), "NBT rebuild reused F root");
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(
                        MaidBrainProfile.FAITHFUL_TLM,
                        MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL) == MaidPolicyRuntime.SelectionResult.SELECTED,
                    "Same-physics F control could not replace F");
                assertContract(helper, brain.peekFaithfulBrain(), MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL);
                previous[0] = brain.peekFaithfulBrain();
                nativeRead(f.maid);
            } else {
                assertContract(helper, brain.peekFaithfulBrain(), MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL);
                helper.assertTrue(previous[0] != brain.peekFaithfulBrain(), "NBT rebuild reused F-R root");
                var control = brain.peekPolicyRuntime();
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(
                        MaidBrainProfile.ENHANCED,
                        MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL)
                        == MaidPolicyRuntime.SelectionResult.UNAVAILABLE && control == brain.peekPolicyRuntime()
                        && !control.isRetired(),
                    "Invalid root/flight pair revoked the current root");
                brain.peekFaithfulBrain()
                    .getActor()
                    .rangedBow()
                    .setFlightProfileForDevelopment(ModernFlightProfile.SOURCE_ORIGINAL);
                helper.assertTrue(
                    !brain.peekFaithfulBrain()
                        .hasFlightContract(),
                    "Observer override was mislabeled as the selected F-R contract");
                helper.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(
                        MaidBrainProfile.FAITHFUL_TLM,
                        MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL) == MaidPolicyRuntime.SelectionResult.SELECTED
                        && control.isRetired(),
                    "Explicit reselection did not rebuild a corrupted contract");
                assertContract(helper, brain.peekFaithfulBrain(), MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL);
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(item, f.maid.getOffhandItem()),
                    "Root contract transition changed the complete item");
                System.out.println(
                    "TLMNH_F_FLIGHT_FACTORY_PASS source=original control=actual_static nbt=fresh_roots observer_override=detected items=retained");
                return true;
            }
            phase[0]++;
            next[0] = f.maid.ticksExisted + 4;
            return false;
        });
    }

    private static void nativeRead(com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        var tag = new NBTTagCompound();
        maid.writeToNBT(tag);
        maid.readFromNBT(tag);
    }

    private static void assertContract(GameTestHelper helper, MaidFaithfulBrain root,
        MaidFaithfulFlightProfile profile) {
        helper.assertTrue(
            root != null && root.getFlightProfile() == profile
                && root.hasFlightContract()
                && root.getActor()
                    .rangedBow()
                    .flightProfile() == profile.bowProfile(),
            "Full F factory flight contract differs: " + profile);
    }
}
