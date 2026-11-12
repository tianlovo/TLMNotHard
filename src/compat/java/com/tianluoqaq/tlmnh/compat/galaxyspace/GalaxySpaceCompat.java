package com.tianluoqaq.tlmnh.compat.galaxyspace;

import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** GalaxySpace 二阶隔热服与扩展氧气装置适配描述符 */
public final class GalaxySpaceCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.GALAXY_SPACE;
    public static final GalaxySpaceCompat INSTANCE = new GalaxySpaceCompat();

    private GalaxySpaceCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return GalaxySpaceIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return GalaxySpaceIds.REVIEWED_VERSION;
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
        return Collections.singletonList(KnownModIds.GALACTICRAFT_CORE);
    }

    @Override
    public String capabilitySummary() {
        return GalaxySpaceIds.CAPABILITY_SUMMARY;
    }
}
