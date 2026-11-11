package com.tianluoqaq.tlmnh.compat.applecore;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 读取食物饥饿恢复量与饱和度倍率 */
public final class AppleCoreCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.APPLE_CORE;

    public static final AppleCoreCompat INSTANCE = new AppleCoreCompat();

    private AppleCoreCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return AppleCoreIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return AppleCoreIds.REVIEWED_VERSION;
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
        return AppleCoreIds.CAPABILITY_SUMMARY;
    }
}
