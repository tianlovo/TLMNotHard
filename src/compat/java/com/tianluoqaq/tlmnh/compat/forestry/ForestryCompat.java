package com.tianluoqaq.tlmnh.compat.forestry;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** Forestry 模组描述元数据 */
public final class ForestryCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.FORESTRY;

    public static final ForestryCompat INSTANCE = new ForestryCompat();

    private ForestryCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return ForestryIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return ForestryIds.REVIEWED_VERSION;
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
        return ForestryIds.CAPABILITY_SUMMARY;
    }
}
