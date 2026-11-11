package com.tianluoqaq.tlmnh.compat.angelica;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 客户端矩阵栈深度读取与图集纹理过滤状态窗口保存 */
public final class AngelicaCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.ANGELICA;
    public static final String PACKAGE = "angelica";
    public static final String REVIEWED_VERSION = "2.2.43";
    public static final AngelicaCompat INSTANCE = new AngelicaCompat();

    private AngelicaCompat() {}

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
        return SideScope.CLIENT;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.INIT;
    }

    @Override
    public String capabilitySummary() {
        return "客户端矩阵栈深度读取与图集纹理过滤状态窗口保存";
    }
}
