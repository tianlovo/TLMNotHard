package com.tianluoqaq.tlmnh.compat.ysmu;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 乘客动画实体 ID 匹配尾部女仆别名回退 */
public final class YsmuCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.YSMU;


    public static final YsmuCompat INSTANCE = new YsmuCompat();

    private YsmuCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return YsmuIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return YsmuIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.CLIENT;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.MIXIN;
    }

    @Override
    public String capabilitySummary() {
        return YsmuDiagnostics.CAPABILITY_SUMMARY;
    }
}
