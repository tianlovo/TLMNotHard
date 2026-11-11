package com.tianluoqaq.tlmnh.compat.tinkersdefense;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 模块化圆盾与鸢盾状态核验及副手渲染变换 */
public final class TinkersDefenseCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.TINKERS_DEFENSE;
    public static final String PACKAGE = "tinkersdefense";
    public static final String REVIEWED_VERSION = "1.3.4";
    public static final TinkersDefenseCompat INSTANCE = new TinkersDefenseCompat();

    private TinkersDefenseCompat() {}

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
        return Phase.INIT;
    }

    @Override
    public List<String> requiredPeerModIds() {
        return Collections.unmodifiableList(Arrays.asList(KnownModIds.TCONSTRUCT, KnownModIds.BATTLEGEAR2));
    }

    @Override
    public String capabilitySummary() {
        return "模块化圆盾与鸢盾状态核验及副手渲染变换";
    }
}
