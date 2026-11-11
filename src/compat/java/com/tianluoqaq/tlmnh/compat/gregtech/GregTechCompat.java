package com.tianluoqaq.tlmnh.compat.gregtech;

import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 元生成近战武器能力审查与元生成食物效果解析 */
public final class GregTechCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.GREGTECH;
    public static final String PACKAGE = "gregtech";
    public static final String REVIEWED_VERSION = "5.09.54.265";
    public static final GregTechCompat INSTANCE = new GregTechCompat();

    private GregTechCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return REVIEWED_VERSION;
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
        return "元生成近战武器能力审查与元生成食物效果解析";
    }
}
