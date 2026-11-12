package com.tianluoqaq.tlmnh.compat.ic2;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** IndustrialCraft 2 护甲与喷气背包适配模块描述符 */
public final class Ic2Compat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.IC2;
    public static final Ic2Compat INSTANCE = new Ic2Compat();

    private Ic2Compat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return Ic2Ids.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return Ic2Ids.REVIEWED_VERSION;
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
        return Ic2Ids.CAPABILITY_SUMMARY;
    }
}
