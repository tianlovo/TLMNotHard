package com.tianluoqaq.tlmnh.compat.galacticraftcore;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 维度箭矢重力查询与发射前无副作用核验 */
public final class GalacticraftCoreCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.GALACTICRAFT_CORE;


    public static final GalacticraftCoreCompat INSTANCE = new GalacticraftCoreCompat();

    private GalacticraftCoreCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return GalacticraftIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return GalacticraftIds.REVIEWED_VERSION;
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
        return GalacticraftIds.CAPABILITY_SUMMARY;
    }
}
