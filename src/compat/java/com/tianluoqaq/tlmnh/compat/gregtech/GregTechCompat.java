package com.tianluoqaq.tlmnh.compat.gregtech;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 元生成近战武器能力审查、元生成食物效果解析与普通维护仓检修 */
public final class GregTechCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.GREGTECH;

    public static final GregTechCompat INSTANCE = new GregTechCompat();

    private GregTechCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return GregTechIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return GregTechIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.COMMON;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.POST_INIT;
    }

    @Override
    public String capabilitySummary() {
        return GregTechIds.CAPABILITY_SUMMARY;
    }
}
