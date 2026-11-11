package com.tianluoqaq.tlmnh.compat.backhand;

import com.tianluoqaq.tlmnh.common.protection.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 玩家副手联动能力边界与开发期交互夹具 */
public final class BackhandCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.BACKHAND;
    public static final String PACKAGE = "backhand";
    public static final String REVIEWED_VERSION = "1.8.18";
    public static final BackhandCompat INSTANCE = new BackhandCompat();

    private BackhandCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.BOTH;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.RUNTIME;
    }

    @Override
    public String capabilitySummary() {
        return "玩家副手联动能力边界与开发期交互夹具";
    }
}
