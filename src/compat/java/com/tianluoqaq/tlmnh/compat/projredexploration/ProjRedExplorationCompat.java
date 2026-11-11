package com.tianluoqaq.tlmnh.compat.projredexploration;

import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.compat.CompatDescriptor;

/** ProjectRed Exploration 模组描述元数据 */
public final class ProjRedExplorationCompat implements CompatDescriptor {

    public static final String MOD_ID = KnownModIds.PROJ_RED_EXPLORATION;

    public static final ProjRedExplorationCompat INSTANCE = new ProjRedExplorationCompat();

    private ProjRedExplorationCompat() {}

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String packageSegment() {
        return ProjRedExplorationIds.PACKAGE;
    }

    @Override
    public String reviewedVersion() {
        return ProjRedExplorationIds.REVIEWED_VERSION;
    }

    @Override
    public SideScope sideScope() {
        return SideScope.SERVER;
    }

    @Override
    public Phase primaryPhase() {
        return Phase.PRE_INIT;
    }

    @Override
    public List<String> requiredPeerModIds() {
        return Collections.singletonList(KnownModIds.PROJ_RED_CORE);
    }

    @Override
    public String capabilitySummary() {
        return ProjRedExplorationIds.CAPABILITY_SUMMARY;
    }
}
