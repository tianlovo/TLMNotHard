package com.tianluoqaq.tlmnh.common.combat.ballistics;

import java.util.Arrays;

/** 只拟合连续可见的位置样本；失去视野、换目标或瞬移后不沿用旧速度 */
public final class ObservedTargetMotion {

    private final BallisticVector[] positions = new BallisticVector[BallisticsTuning.CAPACITY];
    private final long[] times = new long[BallisticsTuning.CAPACITY];
    private Object identity;
    private int count;

    public BallisticVector observe(Object identity, long tick, BallisticVector position) {
        if (identity == null || position == null)
            throw new IllegalArgumentException(BallisticsDiagnostics.MSG_MISSING_OBSERVATION);
        if (this.identity != identity
            || count > 0
                && (tick <= times[count - 1]
                    || tick - times[count - 1] > BallisticsTuning.ARROW_INTERCEPT_LAYOUT_B_OFFSET
                    || position.subtract(positions[count - 1])
                        .length() / (tick - times[count - 1]) > BallisticsTuning.OBSERVED_TARGET_OBSERVE_FACTOR))
            clear();
        this.identity = identity;
        if (count == BallisticsTuning.CAPACITY) {
            System.arraycopy(
                positions,
                BallisticsTuning.MIN_MAX_TICKS_THRESHOLD,
                positions,
                BallisticsTuning.REJECTED_INITIAL_VALUE,
                BallisticsTuning.CAPACITY - 1);
            System.arraycopy(
                times,
                BallisticsTuning.MIN_MAX_TICKS_THRESHOLD,
                times,
                BallisticsTuning.REJECTED_INITIAL_VALUE,
                BallisticsTuning.CAPACITY - 1);
            count--;
        }
        positions[count] = position;
        times[count++] = tick;
        return velocity();
    }

    public BallisticVector velocity() {
        if (count < BallisticsTuning.ARROW_INTERCEPT_LAYOUT_B_OFFSET) return new BallisticVector(
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE);
        double meanTime = 0;
        var meanPosition = new BallisticVector(
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE);
        for (int i = 0; i < count; i++) {
            meanTime += times[i] - times[BallisticsTuning.REJECTED_INITIAL_VALUE];
            meanPosition = meanPosition.add(positions[i]);
        }
        meanTime /= count;
        meanPosition = meanPosition.scale(BallisticsTuning.UNIT_AXIS_SCALE_D / count);
        double denominator = 0;
        var numerator = new BallisticVector(
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE,
            BallisticsTuning.REJECTED_INITIAL_VALUE);
        for (int i = 0; i < count; i++) {
            double time = times[i] - times[BallisticsTuning.REJECTED_INITIAL_VALUE] - meanTime;
            denominator += time * time;
            numerator = numerator.add(
                positions[i].subtract(meanPosition)
                    .scale(time));
        }
        return denominator > 0 ? numerator.scale(BallisticsTuning.MIN_MAX_TICKS_THRESHOLD / denominator)
            : new BallisticVector(
                BallisticsTuning.REJECTED_INITIAL_VALUE,
                BallisticsTuning.REJECTED_INITIAL_VALUE,
                BallisticsTuning.REJECTED_INITIAL_VALUE);
    }

    /** 只用最近连续两次观测，供相同弹道下的匀速提前量对照；沿用同一失效与瞬移过滤 */
    public BallisticVector latestVelocity() {
        if (count < BallisticsTuning.ARROW_INTERCEPT_LAYOUT_B_OFFSET) return BallisticVector.ZERO;
        return positions[count - 1].subtract(positions[count - BallisticsTuning.ARROW_INTERCEPT_LAYOUT_B_OFFSET])
            .scale(
                BallisticsTuning.UNIT_AXIS_SCALE_D
                    / (times[count - 1] - times[count - BallisticsTuning.ARROW_INTERCEPT_LAYOUT_B_OFFSET]));
    }

    public void clear() {
        Arrays.fill(positions, null);
        identity = null;
        count = BallisticsTuning.REJECTED_INITIAL_VALUE;
    }
}
