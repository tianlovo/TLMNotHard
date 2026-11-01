package com.tianluoqaq.tlmnh.common.combat;

import java.util.function.DoubleSupplier;

/** 固定上游TaskBowAttack与映射Projectile/AbstractArrow的基础箭数值，不含原生预瞄补偿 */
public final class SourceBowShot {

    private SourceBowShot() {}

    public static float speed(float distance) {
        return Math.max(1.6F, Math.min(3.2F, distance / 10F));
    }

    public static float inaccuracy(float distance) {
        return 1F - Math.max(0F, Math.min(.9F, distance / 100F));
    }

    public static double baseDamage(float charge, int difficulty, DoubleSupplier random) {
        return (double) (charge * 2F) + triangle(difficulty * .11, .57425, random);
    }

    public static BallisticVector velocity(BallisticVector delta, float distance, DoubleSupplier random) {
        double length = delta.length();
        var unit = length < 1.0E-4 ? BallisticVector.ZERO
            : new BallisticVector(delta.x / length, delta.y / length, delta.z / length);
        double spread = .0172275 * (double) inaccuracy(distance);
        // 原Projectile先归一化、分别加入triangle，再整体乘速度；不能再用旧版Gaussian接口归一化
        return new BallisticVector(
            unit.x + triangle(0, spread, random),
            unit.y + triangle(0, spread, random),
            unit.z + triangle(0, spread, random)).scale((double) speed(distance));
    }

    private static double triangle(double mean, double spread, DoubleSupplier random) {
        return mean + spread * (random.getAsDouble() - random.getAsDouble());
    }
}
