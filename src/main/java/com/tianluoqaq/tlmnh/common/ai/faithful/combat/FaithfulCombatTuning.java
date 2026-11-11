package com.tianluoqaq.tlmnh.common.ai.faithful.combat;

/** 原版对齐模式近战与远程参数（Tuning） */
public final class FaithfulCombatTuning {

    public static final int MAX_SINCE_THRESHOLD = 200;
    public static final float MODERN_COMBAT_WALK_TO_ATTACK_TARGET_SECOND_FACTOR = .6F;
    public static final int MODERN_COMBAT_TRIGGER_SECOND_PARAM = 20;
    public static final int MODERN_RANGED_STRAFE_DEADLINE = 1200;
    public static final int NEXT_RANGED_WORLD_INT_SINGLE_UNIT = 1;

    private FaithfulCombatTuning() {}
}
