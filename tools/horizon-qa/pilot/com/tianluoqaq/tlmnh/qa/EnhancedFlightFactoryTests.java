package com.tianluoqaq.tlmnh.qa;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime.SelectionResult;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedFlightFactoryTests {

    private EnhancedFlightFactoryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "enhanced_flight_factory")
    public static void threeNativeSpecificationsSurviveNativeReadAndRejectInvalidOrPollutedContracts(GameTestHelper h) {
        var f = new ModernCoreTests.Fixture(h, false);
        var coordinator = f.maid.getBrainCoordinator();
        var profiles = new ModernFlightProfile[] { ModernFlightProfile.NATIVE_STATIC_COMPENSATION,
            ModernFlightProfile.NATIVE_LAST_SAMPLE_COMPENSATION, ModernFlightProfile.NATIVE_MOTION_COMPENSATION };
        int[] phase = { 0 }, at = { 4 };
        EnhancedCoreBrain[] previous = { null };
        h.succeedWhen(() -> {
            if (f.maid.ticksExisted < at[0]) return false;
            int index = phase[0] / 2;
            var spec = profiles[index];
            if (phase[0] % 2 == 0) {
                var result = f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED, spec);
                h.assertTrue(
                    result == SelectionResult.SELECTED || result == SelectionResult.UNCHANGED,
                    "Native N factory rejected specification");
                assertContract(h, coordinator.peekEnhancedCore(), spec);
                var active = coordinator.peekPolicyRuntime();
                h.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED, spec)
                        == SelectionResult.UNCHANGED && coordinator.peekPolicyRuntime() == active,
                    "UNCHANGED rebuilt or altered a matching contract");
                previous[0] = coordinator.peekEnhancedCore();
                var tag = new NBTTagCompound();
                f.maid.writeToNBT(tag);
                f.maid.readFromNBT(tag);
                h.assertTrue(coordinator.peekEnhancedCore() == null, "Native read retained the previous N root");
            } else {
                assertContract(h, coordinator.peekEnhancedCore(), spec);
                h.assertTrue(coordinator.peekEnhancedCore() != previous[0], "Native read reused N root object");
                var active = coordinator.peekPolicyRuntime();
                h.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM, spec)
                        == SelectionResult.UNAVAILABLE
                        && f.maid.selectBrainProfileForDevelopment(
                            MaidBrainProfile.ENHANCED,
                            ModernFlightProfile.SOURCE_ORIGINAL) == SelectionResult.UNAVAILABLE
                        && coordinator.peekPolicyRuntime() == active
                        && !active.isRetired(),
                    "Invalid root/spec revoked the active root");
                coordinator.peekEnhancedCore()
                    .actor()
                    .rangedBow()
                    .setFlightProfileForDevelopment(ModernFlightProfile.SOURCE_ORIGINAL);
                h.assertTrue(
                    !coordinator.peekEnhancedCore()
                        .hasFlightContract(),
                    "Post-factory actuator pollution was hidden");
                h.assertTrue(
                    f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED, spec) == SelectionResult.SELECTED
                        && active.isRetired(),
                    "Explicit reselection failed to replace the polluted contract");
                assertContract(h, coordinator.peekEnhancedCore(), spec);
                if (index == profiles.length - 1) return true;
            }
            phase[0]++;
            at[0] = f.maid.ticksExisted + 4;
            return false;
        });
    }

    private static void assertContract(GameTestHelper h, EnhancedCoreBrain root, ModernFlightProfile spec) {
        h.assertTrue(
            root != null && root.getFlightProfile() == spec
                && root.hasFlightContract()
                && root.actor()
                    .rangedBow()
                    .flightProfile() == spec,
            "Actual N root/actuator differs from creation specification");
    }
}
