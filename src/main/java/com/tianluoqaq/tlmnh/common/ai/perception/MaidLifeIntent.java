package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.Objects;

/** 本轮已过滤候选的动作建议，持有观测但不授予执行权限 */
public final class MaidLifeIntent {

    public enum Action {
        BED,
        STEAL,
        PLACE
    }

    private final Action action;
    private final MaidLifeCandidate candidate;
    private final double distanceSq;

    public MaidLifeIntent(Action action, MaidLifeCandidate candidate, double distanceSq) {
        this.action = Objects.requireNonNull(action, "action");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        if (!Double.isFinite(distanceSq) || distanceSq < 0
            || (action == Action.BED) != (candidate.getKind() == MaidLifeCandidate.Kind.BED))
            throw new IllegalArgumentException("Invalid life intent");
        this.distanceSq = distanceSq;
    }

    public Action getAction() {
        return action;
    }

    public MaidLifeCandidate getCandidate() {
        return candidate;
    }

    public double getDistanceSq() {
        return distanceSq;
    }
}
