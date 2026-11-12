package com.tianluoqaq.tlmnh.compat.hungeroverhaul;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** HungerOverhaul 饥饿与自然回血参数适配描述符 */
public final class HungerOverhaulCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.HUNGER_OVERHAUL;
    public static final HungerOverhaulCompat INSTANCE = new HungerOverhaulCompat();

    private HungerOverhaulCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return HungerOverhaulIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return HungerOverhaulIds.REVIEWED_VERSION;
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
        return HungerOverhaulIds.CAPABILITY_SUMMARY;
    }
}
