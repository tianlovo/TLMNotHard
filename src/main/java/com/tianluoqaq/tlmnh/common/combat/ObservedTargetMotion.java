package com.tianluoqaq.tlmnh.common.combat;

/** 只拟合连续可见的位置样本；失去视野、换目标或瞬移后不沿用旧速度 */
public final class ObservedTargetMotion {

    private static final int CAPACITY = 5;
    private final BallisticVector[] positions = new BallisticVector[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private Object identity;
    private int count;

    public BallisticVector observe(Object identity, long tick, BallisticVector position) {
        if (identity == null || position == null) throw new IllegalArgumentException("Missing observation");
        if (this.identity != identity || count > 0 && (tick <= times[count - 1] || tick - times[count - 1] > 2
            || position.subtract(positions[count - 1])
                .length() / (tick - times[count - 1]) > 1.5))
            clear();
        this.identity = identity;
        if (count == CAPACITY) {
            System.arraycopy(positions, 1, positions, 0, CAPACITY - 1);
            System.arraycopy(times, 1, times, 0, CAPACITY - 1);
            count--;
        }
        positions[count] = position;
        times[count++] = tick;
        return velocity();
    }

    public BallisticVector velocity() {
        if (count < 2) return new BallisticVector(0, 0, 0);
        double meanTime = 0;
        var meanPosition = new BallisticVector(0, 0, 0);
        for (int i = 0; i < count; i++) {
            meanTime += times[i] - times[0];
            meanPosition = meanPosition.add(positions[i]);
        }
        meanTime /= count;
        meanPosition = meanPosition.scale(1.0 / count);
        double denominator = 0;
        var numerator = new BallisticVector(0, 0, 0);
        for (int i = 0; i < count; i++) {
            double time = times[i] - times[0] - meanTime;
            denominator += time * time;
            numerator = numerator.add(
                positions[i].subtract(meanPosition)
                    .scale(time));
        }
        return denominator > 0 ? numerator.scale(1 / denominator) : new BallisticVector(0, 0, 0);
    }

    /** 只用最近连续两次观测，供相同弹道下的匀速提前量对照；沿用同一失效与瞬移过滤 */
    public BallisticVector latestVelocity() {
        if (count < 2) return BallisticVector.ZERO;
        return positions[count - 1].subtract(positions[count - 2])
            .scale(1.0 / (times[count - 1] - times[count - 2]));
    }

    public void clear() {
        java.util.Arrays.fill(positions, null);
        identity = null;
        count = 0;
    }
}
