package com.tianluoqaq.tlmnh.common.life.hunger;

/** 女仆饥饿状态机版本或数据异常单次告警文案与规则名 */
public final class MaidHungerDiagnostics {

    public static final String GAMERULE_NATURAL_REGEN = "naturalRegeneration";
    public static final String WARN_PAUSED_INVALID_TAG = "[TLMNH] Maid hunger state tag invalid or from newer schema; entering safe paused mode without overwriting stored payload.";

    private MaidHungerDiagnostics() {}
}
