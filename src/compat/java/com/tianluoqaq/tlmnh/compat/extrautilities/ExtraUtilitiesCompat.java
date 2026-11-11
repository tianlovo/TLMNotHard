package com.tianluoqaq.tlmnh.compat.extrautilities;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** ExtraUtilities 模组描述元数据 */
public final class ExtraUtilitiesCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.EXTRA_UTILITIES;

    public static final ExtraUtilitiesCompat INSTANCE = new ExtraUtilitiesCompat();

    private ExtraUtilitiesCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return ExtraUtilitiesIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return ExtraUtilitiesIds.REVIEWED_VERSION;
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
        return ExtraUtilitiesIds.CAPABILITY_SUMMARY;
    }
}
