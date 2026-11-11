package com.tianluoqaq.tlmnh.common.ai.navigation;

import com.tianluoqaq.tlmnh.common.ai.navigation.eval.MaidPathSearch;
import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidNavigationActor;

/** Water route subtype retained for the versioned Indev96 development fixtures */
public final class MaidAmphibiousPath extends MaidModernPath {

    public MaidAmphibiousPath(MaidPathSearch.Result result) {
        super(result);
    }

    public MaidAmphibiousPath(MaidPathSearch.Result result, MaidNavigationActor.Point target) {
        super(result, target);
    }
}
