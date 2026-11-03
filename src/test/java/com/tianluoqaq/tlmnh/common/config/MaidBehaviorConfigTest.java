package com.tianluoqaq.tlmnh.common.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;

import net.minecraftforge.common.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class MaidBehaviorConfigTest {

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();
    private Field minecraftHome;
    private Object previousHome;

    @Before
    public void provideOfflineForgeHome() throws ReflectiveOperationException {
        // Native Configuration(File) reads FMLInjectionData.data()[6], normally filled by the launcher.
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        assertEquals(File.class, minecraftHome.getType());
        previousHome = minecraftHome.get(null);
        minecraftHome.set(null, files.getRoot());
    }

    @After
    public void restoreDefault() throws IllegalAccessException {
        try {
            MaidBehaviorConfig.load(new Configuration());
        } finally {
            if (minecraftHome != null) minecraftHome.set(null, previousHome);
        }
    }

    @Test
    public void absentPropertyPreservesLegacyAndOnlyCompleteNativeEnhancedIsProductionEligible() {
        MaidBehaviorConfig.load(new Configuration());
        assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, MaidBehaviorConfig.profile());
        assertFalse(MaidBehaviorConfig.usedFallback());
        assertEquals(MaidBrainProfile.ENHANCED, MaidBrainProfile.fromProductionId(" Enhanced "));
        for (var profile : MaidBrainProfile.values()) {
            boolean production = profile == MaidBrainProfile.LEGACY_PROTOTYPE || profile == MaidBrainProfile.ENHANCED;
            assertEquals(!production, profile.isDevelopmentOnly());
            if (production) assertEquals(profile, MaidBrainProfile.fromProductionId(profile.getId()));
            else assertNull(MaidBrainProfile.fromProductionId(profile.getId()));
        }
        assertNull(MaidBrainProfile.fromProductionId(null));
    }

    @Test
    public void unknownAndResearchValuesRemainOnDiskWhileRuntimeSafelyUsesLegacy() throws Exception {
        for (String value : new String[] { "future_native", "faithful_tlm", "follow_core_component",
            "enhanced_core_component", "" }) {
            var file = files.newFile();
            var config = new Configuration(file);
            config.get("behavior", "profile", value)
                .set(value);
            MaidBehaviorConfig.load(config);
            assertEquals(value, MaidBehaviorConfig.configuredValue());
            assertTrue(MaidBehaviorConfig.usedFallback());
            assertEquals(MaidBrainProfile.LEGACY_PROTOTYPE, MaidBehaviorConfig.profile());
            config.save();
            var restored = new Configuration(file);
            restored.load();
            assertEquals(
                value,
                restored.get("behavior", "profile", "must-not-replace")
                    .getString());
        }
        var valid = new Configuration();
        valid.get("behavior", "profile", "enhanced")
            .set("enhanced");
        MaidBehaviorConfig.load(valid);
        assertEquals(MaidBrainProfile.ENHANCED, MaidBehaviorConfig.profile());
        assertFalse(MaidBehaviorConfig.usedFallback());
    }
}
