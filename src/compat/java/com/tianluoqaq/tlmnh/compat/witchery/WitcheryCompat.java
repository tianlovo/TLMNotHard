package com.tianluoqaq.tlmnh.compat.witchery;

import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 复活交付阶段的女仆初始体型兼容处理 */
public final class WitcheryCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.WITCHERY;
    public static final String PACKAGE = "witchery";
    public static final String REVIEWED_VERSION = "0.24.1";
    public static final WitcheryCompat INSTANCE = new WitcheryCompat();

    private WitcheryCompat() {}

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
        return Phase.RUNTIME;
    }

    @Override
    public String capabilitySummary() {
        return "复活交付阶段的女仆初始体型兼容处理";
    }
}
