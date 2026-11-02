package com.tianluoqaq.tlmnh.common.config;

import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

/** 原攻击忽略名单由服务端读取，实体ID采用目标版本的注册名 */
public final class MaidCombatConfig {

    private static String[] ignored = { "mekanism:robit" };
    private static String[] rangedIgnored = {};

    private MaidCombatConfig() {}

    public static void load(Configuration config) {
        ignored = config.getStringList(
            "MaidAttackIgnore",
            "combat",
            new String[] { "mekanism:robit" },
            "女仆不攻击的实体注册ID / Entity registry IDs ignored by maid combat");
        rangedIgnored = config.getStringList(
            "MaidRangedAttackIgnore",
            "combat",
            new String[] {},
            "女仆箭忽略碰撞的实体注册ID / Entity registry IDs ignored by maid arrow impact");
    }

    public static boolean ignored(String id) {
        return Arrays.asList(ignored)
            .contains(id);
    }

    public static boolean rangedIgnored(String id) {
        return Arrays.asList(rangedIgnored)
            .contains(id);
    }

    public static String[] ignoredTypes() {
        return ignored.clone();
    }

    public static String[] rangedIgnoredTypes() {
        return rangedIgnored.clone();
    }
}
