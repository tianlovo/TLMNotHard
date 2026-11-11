package com.tianluoqaq.tlmnh.common.config;

import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatRangePolicy;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;

/** 原攻击忽略名单由服务端读取，实体ID采用目标版本的注册名 */
public final class MaidCombatConfig {

    public static int ownerCombatAcquireRange = MaidConfigConfigSpec.CHAT_CONFIG_GET_INT_FOURTH_PARAM,
        ownerCombatRetainRange = MaidConfigConfigSpec.COMBAT_CONFIG_OWNER_COMBAT_RETAIN_RANGE;

    private static String[] ignored = { MaidConfigConfigSpec.ID_MEKANISM_ROBIT };
    private static String[] rangedIgnored = {};

    private MaidCombatConfig() {}

    public static void load(Configuration config) {
        int acquire = config.getInt(
            MaidConfigConfigSpec.ID_OWNERCOMBATACQUIRERANGE,
            MaidConfigConfigSpec.ID_COMBAT,
            MaidConfigConfigSpec.CHAT_CONFIG_GET_INT_FOURTH_PARAM,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.COMBAT_CONFIG_OWNER_COMBAT_RETAIN_RANGE,
            MaidConfigConfigSpec.MSG_ENHANCED_OWNER_CENTERED_TARGET_ACQUISITION);
        ownerCombatRetainRange = config.getInt(
            MaidConfigConfigSpec.ID_OWNERCOMBATRETAINRANGE,
            MaidConfigConfigSpec.ID_COMBAT,
            MaidConfigConfigSpec.COMBAT_CONFIG_OWNER_COMBAT_RETAIN_RANGE,
            MaidConfigConfigSpec.COMBAT_CONFIG_LOAD_PARAM,
            MaidConfigConfigSpec.COMBAT_CONFIG_OWNER_COMBAT_RETAIN_RANGE,
            MaidConfigConfigSpec.MSG_ENHANCED_OWNER_CENTERED_TARGET_RETENTION);
        ownerCombatAcquireRange = CombatRangePolicy.normalizedAcquire(acquire, ownerCombatRetainRange);
        if (acquire != ownerCombatAcquireRange) {
            config
                .get(
                    MaidConfigConfigSpec.ID_COMBAT,
                    MaidConfigConfigSpec.ID_OWNERCOMBATACQUIRERANGE,
                    MaidConfigConfigSpec.CHAT_CONFIG_GET_INT_FOURTH_PARAM)
                .set(ownerCombatAcquireRange);
            ModContext.LOG.warn(
                MaidConfigConfigSpec.MSG_NORMALIZED_OWNERCOMBATACQUIRERANGE_FROM_TO_RETAI,
                acquire,
                ownerCombatAcquireRange,
                ownerCombatRetainRange);
        }
        ignored = config.getStringList(
            MaidConfigConfigSpec.KEY_MAID_ATTACK_IGNORE,
            MaidConfigConfigSpec.ID_COMBAT,
            new String[] { MaidConfigConfigSpec.ID_MEKANISM_ROBIT },
            MaidConfigConfigSpec.MSG_ID_ENTITY_REGISTRY_IDS_IGNORED);
        rangedIgnored = config.getStringList(
            MaidConfigConfigSpec.KEY_MAID_RANGED_ATTACK_IGNORE,
            MaidConfigConfigSpec.ID_COMBAT,
            new String[] {},
            MaidConfigConfigSpec.MSG_ID_ENTITY_REGISTRY_IDS_IGNORED_2);
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
