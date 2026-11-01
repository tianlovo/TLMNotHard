package com.tianluoqaq.tlmnh.common.ai.navigation;

/** Retains the Indev96 water-only entry point for its frozen component fixtures */
public final class MaidAmphibiousSearch {

    private MaidAmphibiousSearch() {}

    public static Result find(MaidPathContext actor, int x, int y, int z, float range, int accuracy, int maxVisited) {
        return new Result(MaidPathSearch.find(actor, true, x, y, z, range, accuracy, maxVisited));
    }

    public static final class Result extends MaidPathSearch.Result {

        private Result(MaidPathSearch.Result source) {
            super(source.nodes, source.canReach, source.visited, source.targetX, source.targetY, source.targetZ, true);
        }
    }
}
