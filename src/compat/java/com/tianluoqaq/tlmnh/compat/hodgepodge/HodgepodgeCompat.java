package com.tianluoqaq.tlmnh.compat.hodgepodge;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 世界存档账本写前同步屏障 */
public final class HodgepodgeCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.HODGEPODGE;


    public static final HodgepodgeCompat INSTANCE = new HodgepodgeCompat();

    private HodgepodgeCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return HodgepodgeIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return HodgepodgeIds.REVIEWED_VERSION;
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
        return HodgepodgeIds.CAPABILITY_SUMMARY;
    }
}
