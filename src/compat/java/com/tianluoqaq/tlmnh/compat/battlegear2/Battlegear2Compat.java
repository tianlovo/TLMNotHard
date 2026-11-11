package com.tianluoqaq.tlmnh.compat.battlegear2;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 盾牌识别、格挡角、减伤、插箭计数与副手渲染变换 */
public final class Battlegear2Compat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.BATTLEGEAR2;


    public static final Battlegear2Compat INSTANCE = new Battlegear2Compat();

    private Battlegear2Compat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return BattlegearIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return BattlegearIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.BOTH;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.INIT;
    }

    @Override
    public String capabilitySummary() {
        return BattlegearIds.CAPABILITY_SUMMARY;
    }
}
