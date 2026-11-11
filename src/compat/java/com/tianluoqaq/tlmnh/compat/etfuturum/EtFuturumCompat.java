package com.tianluoqaq.tlmnh.compat.etfuturum;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 气泡柱、蜂蜜块、船只乘客、水流物理、旗帜头颅渲染与方块音效 */
public final class EtFuturumCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.ET_FUTURUM;

    public static final EtFuturumCompat INSTANCE = new EtFuturumCompat();

    private EtFuturumCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return EtFuturumIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return EtFuturumIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.BOTH;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.INIT;
    }

    @Override
    public String capabilitySummary() {
        return EtFuturumIds.CAPABILITY_SUMMARY;
    }
}
