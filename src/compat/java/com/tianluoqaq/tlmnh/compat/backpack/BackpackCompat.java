package com.tianluoqaq.tlmnh.compat.backpack;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** Eydamos 背包模组描述元数据 */
public final class BackpackCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.BACKPACK;

    public static final BackpackCompat INSTANCE = new BackpackCompat();

    private BackpackCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return BackpackIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return BackpackIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.SERVER;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.PRE_INIT;
    }

    @Override
    public String capabilitySummary() {
        return BackpackIds.CAPABILITY_SUMMARY;
    }
}
