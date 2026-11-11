package com.tianluoqaq.tlmnh.compat.twilightforest;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 暮色森林维度的墓碑专属贴图解析 */
public final class TwilightForestCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.TWILIGHT_FOREST;

    public static final TwilightForestCompat INSTANCE = new TwilightForestCompat();

    private TwilightForestCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return TwilightForestIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return TwilightForestIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.CLIENT;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.INIT;
    }

    @Override
    public String capabilitySummary() {
        return TwilightForestIds.CAPABILITY_SUMMARY;
    }
}
