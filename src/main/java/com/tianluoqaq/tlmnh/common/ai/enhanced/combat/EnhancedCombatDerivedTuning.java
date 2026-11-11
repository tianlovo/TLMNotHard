package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import com.tianluoqaq.tlmnh.common.combat.MaidCombatTuning;

/** 增强战斗动作执行与跳劈参数（派生计算常量） */
public final class EnhancedCombatDerivedTuning {

    public static final double BOW_STAND_DISTANCE = Math.sqrt(MaidCombatTuning.RANGE_SQ);

    private EnhancedCombatDerivedTuning() {}
}
