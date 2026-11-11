package com.tianluoqaq.tlmnh.compat.endlessids;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 区块区段扩展方块 ID 与元数据层只读解码 */
public final class EndlessIdsCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.ENDLESS_IDS;

    public static final EndlessIdsCompat INSTANCE = new EndlessIdsCompat();

    private EndlessIdsCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return EndlessIdsIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return EndlessIdsIds.REVIEWED_VERSION;
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
        return EndlessIdsDiagnostics.CAPABILITY_SUMMARY;
    }
}
