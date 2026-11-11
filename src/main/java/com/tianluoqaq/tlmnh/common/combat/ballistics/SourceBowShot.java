package com.tianluoqaq.tlmnh.common.combat.ballistics;

import java.util.function.DoubleSupplier;

/** 固定上游TaskBowAttack与映射Projectile/AbstractArrow的基础箭数值，不含原生预瞄补偿 */
public final class SourceBowShot {

    private SourceBowShot() {}

    public static float speed(float distance) {
        return Math.max(
            BallisticsTuning.SOURCE_BOW_MAX_FIRST_FACTOR,
            Math.min(
                BallisticsTuning.SOURCE_BOW_MIN_FIRST_FACTOR,
                distance / BallisticsTuning.SOURCE_BOW_MIN_SECOND_FACTOR));
    }

    public static float inaccuracy(float distance) {
        return BallisticsTuning.SOURCE_BOW_INACCURACY_FACTOR - Math.max(
            BallisticsTuning.SOURCE_BOW_MAX_FIRST_FACTOR_2,
            Math.min(
                BallisticsTuning.SOURCE_BOW_MIN_FIRST_FACTOR_2,
                distance / BallisticsTuning.SOURCE_BOW_MIN_SECOND_FACTOR_2));
    }

    public static double baseDamage(float charge, int difficulty, DoubleSupplier random) {
        return (double) (charge * BallisticsTuning.SOURCE_BOW_BASE_DAMAGE_FACTOR) + triangle(
            difficulty * BallisticsTuning.SOURCE_BOW_TRIANGLE_FIRST_FACTOR,
            BallisticsTuning.SOURCE_BOW_TRIANGLE_SECOND_FACTOR,
            random);
    }

    public static BallisticVector velocity(BallisticVector delta, float distance, DoubleSupplier random) {
        double length = delta.length();
        var unit = length < BallisticsTuning.MIN_LENGTH_THRESHOLD_2 ? BallisticVector.ZERO
            : new BallisticVector(delta.x / length, delta.y / length, delta.z / length);
        double spread = BallisticsTuning.SOURCE_BOW_SPREAD * (double) inaccuracy(distance);
        // 原Projectile先归一化、分别加入triangle，再整体乘速度；不能再用旧版Gaussian接口归一化
        return new BallisticVector(
            unit.x + triangle(BallisticsTuning.REJECTED_INITIAL_VALUE, spread, random),
            unit.y + triangle(BallisticsTuning.REJECTED_INITIAL_VALUE, spread, random),
            unit.z + triangle(BallisticsTuning.REJECTED_INITIAL_VALUE, spread, random)).scale((double) speed(distance));
    }

    private static double triangle(double mean, double spread, DoubleSupplier random) {
        return mean + spread * (random.getAsDouble() - random.getAsDouble());
    }
}
