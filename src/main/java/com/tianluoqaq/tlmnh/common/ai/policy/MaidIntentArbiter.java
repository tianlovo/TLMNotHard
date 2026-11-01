package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Comparator;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Intent;

/** 硬分组先于组内效用，滞回只保留本轮仍合法的目标，不持有旧候选授权 */
public final class MaidIntentArbiter {

    public enum Reason {
        BEST_AVAILABLE,
        CONTINUING,
        HYSTERESIS,
        HIGHER_GROUP,
        EMPTY,
        STALE_FRAME
    }

    private static final int MINIMUM_HOLD_TICKS = 20;
    private static final double SWITCH_MARGIN = .05;
    private static final Comparator<Intent> ORDER = Comparator.comparing((Intent intent) -> intent.group)
        .thenComparing(
            Comparator.comparingDouble((Intent intent) -> intent.utility)
                .reversed())
        .thenComparing(intent -> intent.kind)
        .thenComparing(intent -> intent.target);
    private MaidPolicyContext.Token token;
    private MaidIntentFrame.Group group;
    private MaidIntentFrame.Kind kind;
    private String target;
    private long selectedAt, lastTick = Long.MIN_VALUE;
    private Reason reason = Reason.EMPTY;

    public Intent choose(MaidIntentFrame frame) {
        if (!frame.isCurrent()) {
            clear();
            reason = Reason.STALE_FRAME;
            return null;
        }
        if (token != frame.token() || frame.tick() < lastTick) clear();
        token = frame.token();
        lastTick = frame.tick();
        var best = frame.options()
            .stream()
            .min(ORDER)
            .orElse(null);
        if (best == null) {
            clear();
            return null;
        }
        var current = frame.options()
            .stream()
            .filter(option -> option.group == group && option.kind == kind && option.target.equals(target))
            .findFirst()
            .orElse(null);
        Intent selected = best;
        reason = Reason.BEST_AVAILABLE;
        if (current != null) {
            if (best.group.compareTo(current.group) < 0) reason = Reason.HIGHER_GROUP;
            else if (best.sameGoal(current)) {
                selected = current;
                reason = Reason.CONTINUING;
            } else
                if (frame.tick() - selectedAt < MINIMUM_HOLD_TICKS || best.utility <= current.utility + SWITCH_MARGIN) {
                    selected = current;
                    reason = Reason.HYSTERESIS;
                }
        }
        if (selected != current) selectedAt = frame.tick();
        group = selected.group;
        kind = selected.kind;
        target = selected.target;
        return selected;
    }

    public void clear() {
        token = null;
        group = null;
        kind = null;
        target = null;
        lastTick = Long.MIN_VALUE;
        reason = Reason.EMPTY;
    }

    public Reason reason() {
        return reason;
    }
}
