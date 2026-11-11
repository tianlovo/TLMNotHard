package com.tianluoqaq.tlmnh.compat.malisisdoors;

import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** 动画门与栅栏门开关状态读写及战斗碰撞箱采样 */
public final class MalisisDoorsCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.MALISIS_DOORS;
    public static final String PACKAGE = "malisisdoors";
    public static final String REVIEWED_VERSION = "1.19.13-GTNH";
    public static final MalisisDoorsCompat INSTANCE = new MalisisDoorsCompat();

    private MalisisDoorsCompat() {}

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
        return SideScope.COMMON;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.PRE_INIT;
    }

    @Override
    public List<String> requiredPeerModIds() {
        return Collections.singletonList(KnownModIds.MALISIS_CORE);
    }

    @Override
    public String capabilitySummary() {
        return "动画门与栅栏门开关状态读写及战斗碰撞箱采样";
    }
}
