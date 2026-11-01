package com.tianluoqaq.tlmnh.common.ai.navigation;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Path;

/** 增强查询的同步结果；预算等待没有执行A*，不能转换成不可达 */
public final class MaidPathCreation {

    public enum Result {
        FOUND,
        UNREACHABLE,
        WAITING_BUDGET,
        NOT_NAVIGABLE
    }

    public final Result result;
    public final Path path;

    public MaidPathCreation(Result result, Path path) {
        this.result = Objects.requireNonNull(result, "result");
        if ((result == Result.FOUND) != (path != null)) throw new IllegalArgumentException("Path result mismatch");
        this.path = path;
    }
}
