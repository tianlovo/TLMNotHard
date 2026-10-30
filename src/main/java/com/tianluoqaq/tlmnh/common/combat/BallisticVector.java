package com.tianluoqaq.tlmnh.common.combat;

/** 弹道计算的不可变向量，单位为方块和游戏tick */
public final class BallisticVector {

    public static final BallisticVector ZERO = new BallisticVector(0, 0, 0);
    public final double x, y, z;

    public BallisticVector(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Non-finite ballistic vector");
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BallisticVector add(BallisticVector other) {
        return new BallisticVector(x + other.x, y + other.y, z + other.z);
    }

    public BallisticVector subtract(BallisticVector other) {
        return new BallisticVector(x - other.x, y - other.y, z - other.z);
    }

    public BallisticVector scale(double value) {
        return new BallisticVector(x * value, y * value, z * value);
    }

    public double dot(BallisticVector other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double length() {
        return Math.sqrt(dot(this));
    }
}
