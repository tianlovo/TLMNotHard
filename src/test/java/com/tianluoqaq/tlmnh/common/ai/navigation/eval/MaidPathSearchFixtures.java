package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

import java.util.List;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathNode;

/** 在所属包中构造测试路径，供导航所有权与切换用例共用 */
public final class MaidPathSearchFixtures {

    private MaidPathSearchFixtures() {}

    public static MaidPathSearch.Result result(List<MaidPathNode> nodes, boolean reachable, int visited, int x, int y,
        int z, boolean water) {
        return new MaidPathSearch.Result(nodes, reachable, visited, x, y, z, water);
    }
}
