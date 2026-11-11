package com.tianluoqaq.tlmnh.compat.tconstruct;

import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 模块化工具损坏态与破盾斧能力只读解析 */
public final class TConstructCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.TCONSTRUCT;
    public static final String PACKAGE = "tconstruct";
    public static final String REVIEWED_VERSION = "1.14.123-GTNH";
    public static final TConstructCompat INSTANCE = new TConstructCompat();

    private TConstructCompat() {}

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
        return Phase.PRE_INIT;
    }

    @Override
    public String capabilitySummary() {
        return "模块化工具损坏态与破盾斧能力只读解析";
    }
}
