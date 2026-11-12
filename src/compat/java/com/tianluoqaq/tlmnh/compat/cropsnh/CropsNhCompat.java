package com.tianluoqaq.tlmnh.compat.cropsnh;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** CropsNH 2.0.140 育种工作模式适配模块描述符 */
public final class CropsNhCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.CROPS_NH;
    public static final CropsNhCompat INSTANCE = new CropsNhCompat();

    private CropsNhCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return CropsNhIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return CropsNhIds.REVIEWED_VERSION;
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
        return CropsNhIds.CAPABILITY_SUMMARY;
    }
}
