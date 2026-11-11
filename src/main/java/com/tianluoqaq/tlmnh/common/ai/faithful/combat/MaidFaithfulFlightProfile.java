package com.tianluoqaq.tlmnh.common.ai.faithful.combat;

import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;

/** 完整忠实根的弹道输入；F-R保留原决策，仅使用与增强模式相同的实际物理 */
public enum MaidFaithfulFlightProfile {

    SOURCE_ORIGINAL(ModernFlightProfile.SOURCE_ORIGINAL),
    NATIVE_STATIC_CONTROL(ModernFlightProfile.NATIVE_STATIC_COMPENSATION);

    private final ModernFlightProfile bowProfile;

    MaidFaithfulFlightProfile(ModernFlightProfile bowProfile) {
        this.bowProfile = bowProfile;
    }

    public ModernFlightProfile bowProfile() {
        return bowProfile;
    }

    public boolean matches(ModernFlightProfile actual) {
        return bowProfile == actual;
    }
}
