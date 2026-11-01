package com.tianluoqaq.tlmnh.common.ai.navigation;

/** Water route subtype retained for the versioned Indev96 development fixtures */
public final class MaidAmphibiousPath extends MaidModernPath {

    public MaidAmphibiousPath(MaidPathSearch.Result result) {
        super(result);
    }

    public MaidAmphibiousPath(MaidPathSearch.Result result, MaidNavigationActor.Point target) {
        super(result, target);
    }
}
