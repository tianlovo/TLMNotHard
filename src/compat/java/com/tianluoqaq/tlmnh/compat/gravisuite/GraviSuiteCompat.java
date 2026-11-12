package com.tianluoqaq.tlmnh.compat.gravisuite;

import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** GraviSuite 飞行胸甲适配模块描述符 */
public final class GraviSuiteCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.GRAVI_SUITE;
    public static final GraviSuiteCompat INSTANCE = new GraviSuiteCompat();

    private GraviSuiteCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return GraviSuiteIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return GraviSuiteIds.REVIEWED_VERSION;
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
    public List<String> requiredPeerModIds() {
        return Collections.singletonList(KnownModIds.IC2);
    }

    @Override
    public String capabilitySummary() {
        return GraviSuiteIds.CAPABILITY_SUMMARY;
    }
}
