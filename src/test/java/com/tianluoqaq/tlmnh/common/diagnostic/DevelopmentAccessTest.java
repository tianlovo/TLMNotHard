package com.tianluoqaq.tlmnh.common.diagnostic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentHooks.CombatPhase;

/** 验证公共开发观察点单次挂载约束、无操作默认实例与双条件激活门禁 */
public class DevelopmentAccessTest {

    private String originalDevCommands;

    @Before
    public void setUp() {
        originalDevCommands = System.getProperty(DevelopmentFlags.DEV_COMMANDS);
        DevelopmentAccess.resetForTesting();
    }

    @After
    public void tearDown() {
        restore(DevelopmentFlags.DEV_COMMANDS, originalDevCommands);
        DevelopmentAccess.resetForTesting();
    }

    @Test
    public void defaultHooksStayNoopEvenWhenPropertyIsRequested() {
        System.setProperty(DevelopmentFlags.DEV_COMMANDS, "true");
        assertFalse(DevelopmentAccess.isEnabled());
        assertSame(DevelopmentHooks.NONE, DevelopmentAccess.hooks());
        try (var scope = DevelopmentAccess.hooks()
            .measureCombat(null, CombatPhase.OFFER)) {
            assertSame(DevelopmentHooks.SafeCloseable.NOOP, scope);
        }
        assertNull(
            DevelopmentAccess.hooks()
                .currentCombatTrial(null));
    }

    @Test
    public void enabledRequiresBothSystemFlagAndInstalledImplementation() {
        var closed = new AtomicBoolean(false);
        var impl = new DevelopmentHooks() {

            @Override
            public SafeCloseable measureCombat(EntityMaid maid, CombatPhase phase) {
                return () -> closed.set(true);
            }
        };
        System.clearProperty(DevelopmentFlags.DEV_COMMANDS);
        DevelopmentAccess.install(impl);
        assertFalse(DevelopmentAccess.isEnabled());

        System.setProperty(DevelopmentFlags.DEV_COMMANDS, "true");
        assertTrue(DevelopmentAccess.isEnabled());
        try (var ignored = DevelopmentAccess.hooks()
            .measureCombat(null, CombatPhase.SUBMIT)) {
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void installingNoneSentinelIsRejected() {
        DevelopmentAccess.install(DevelopmentHooks.NONE);
    }

    @Test(expected = IllegalStateException.class)
    public void installingSecondDistinctImplementationIsRejected() {
        DevelopmentAccess.install(new DevelopmentHooks() {});
        DevelopmentAccess.install(new DevelopmentHooks() {});
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
