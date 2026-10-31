package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.tianluoqaq.tlmnh.common.ai.perception.MaidLifeIntent;

/** 只比较已许可的本轮意图，不查询世界或重新生成目标 */
public final class MaidLifeSelector {

    public enum Reason {
        NEAREST_VALID_POSITION,
        POLICY_CHOICE
    }

    private static final Comparator<MaidLifeIntent> ORDER = Comparator.comparingDouble(MaidLifeIntent::getDistanceSq)
        .thenComparingInt(
            intent -> intent.getCandidate()
                .getDimension())
        .thenComparingInt(
            intent -> intent.getCandidate()
                .getX())
        .thenComparingInt(
            intent -> intent.getCandidate()
                .getY())
        .thenComparingInt(
            intent -> intent.getCandidate()
                .getZ())
        .thenComparingInt(
            intent -> intent.getAction()
                .ordinal());

    private MaidLifeSelector() {}

    public static Selection nearest(List<MaidLifeIntent> options) {
        return options.stream()
            .min(ORDER)
            .map(intent -> new Selection(intent, Reason.NEAREST_VALID_POSITION))
            .orElse(null);
    }

    public static final class Selection {

        private final MaidLifeIntent intent;
        private final Reason reason;

        public Selection(MaidLifeIntent intent, Reason reason) {
            this.intent = Objects.requireNonNull(intent, "intent");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public MaidLifeIntent getIntent() {
            return intent;
        }

        public Reason getReason() {
            return reason;
        }
    }
}
