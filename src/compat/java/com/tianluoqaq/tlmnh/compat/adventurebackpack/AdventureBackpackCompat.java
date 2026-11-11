package com.tianluoqaq.tlmnh.compat.adventurebackpack;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** AdventureBackpack 模组描述元数据 */
public final class AdventureBackpackCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.ADVENTURE_BACKPACK;

    public static final AdventureBackpackCompat INSTANCE = new AdventureBackpackCompat();

    private AdventureBackpackCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return AdventureBackpackIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return AdventureBackpackIds.REVIEWED_VERSION;
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
        return AdventureBackpackIds.CAPABILITY_SUMMARY;
    }
}
