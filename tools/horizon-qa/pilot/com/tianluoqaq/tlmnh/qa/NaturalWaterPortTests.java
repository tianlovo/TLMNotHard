package com.tianluoqaq.tlmnh.qa;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NaturalWaterPortTests {

    private NaturalWaterPortTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 1600, batch = "natural_water_port")
    public static void collectSameWaterInputsOnFreshNaturalActors(GameTestHelper helper) {
        NaturalFollowPortTests.collect(helper, true);
    }
}
