package com.tianluoqaq.tlmnh.common.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 校验表驱动开发命令的子命令注册规模与关键入口完整性 */
public class DevelopmentCommandTableTest {

    @Test
    public void subcommandTableKeepsAllExpectedEntries() {
        var names = DevelopmentCommand.registeredSubcommands();
        assertEquals(77, names.size());
        assertTrue(names.contains("shield-runtime-status"));
        assertTrue(names.contains("shield-runtime-abort"));
        assertTrue(names.contains("lab-world-test"));
        assertTrue(names.contains("lab-inventory-fixture"));
        assertTrue(names.contains("state-test"));
        assertTrue(names.contains("persist-check"));
    }
}
