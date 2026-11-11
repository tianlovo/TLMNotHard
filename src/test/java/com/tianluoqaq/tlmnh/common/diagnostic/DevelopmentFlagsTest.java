package com.tianluoqaq.tlmnh.common.diagnostic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** 核对开发总开关与子开关之间的门控关系 */
public class DevelopmentFlagsTest {

    @Test
    public void subFeatureRequiresMasterDevCommandsFlag() {
        String prevMaster = System.getProperty(DevelopmentFlags.DEV_COMMANDS);
        String prevImpact = System.getProperty(DevelopmentFlags.QA_PROJECTILE_IMPACT_FALLBACK);
        try {
            System.clearProperty(DevelopmentFlags.DEV_COMMANDS);
            System.setProperty(DevelopmentFlags.QA_PROJECTILE_IMPACT_FALLBACK, "true");
            assertFalse(DevelopmentFlags.isDevCommandsEnabled());
            assertFalse(DevelopmentFlags.isQaProjectileImpactFallback());
            try {
                DevelopmentFlags.requireDevCommands();
                fail("Expected requireDevCommands to reject disabled master flag");
            } catch (IllegalStateException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("Development command required"));
            }

            System.setProperty(DevelopmentFlags.DEV_COMMANDS, "true");
            assertTrue(DevelopmentFlags.isDevCommandsEnabled());
            assertTrue(DevelopmentFlags.isQaProjectileImpactFallback());
            DevelopmentFlags.requireDevCommands();
        } finally {
            restore(DevelopmentFlags.DEV_COMMANDS, prevMaster);
            restore(DevelopmentFlags.QA_PROJECTILE_IMPACT_FALLBACK, prevImpact);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
