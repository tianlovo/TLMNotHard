package com.tianluoqaq.tlmnh.compat.lwjgl3ify;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 客户端底层输入兼容说明与开发期容器键鼠夹具 */
public final class Lwjgl3ifyCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.LWJGL3IFY;


    public static final Lwjgl3ifyCompat INSTANCE = new Lwjgl3ifyCompat();

    private Lwjgl3ifyCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return Lwjgl3ifyIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return Lwjgl3ifyIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.CLIENT;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.RUNTIME;
    }

    @Override
    public String capabilitySummary() {
        return Lwjgl3ifyIds.CAPABILITY_SUMMARY;
    }
}
