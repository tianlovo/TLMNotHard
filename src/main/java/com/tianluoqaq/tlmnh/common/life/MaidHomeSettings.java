package com.tianluoqaq.tlmnh.common.life;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 一条原生 DataWatcher 字符串同步完整家园设置，避免分字段更新时混合新旧活动点 */
public final class MaidHomeSettings {

    public static final MaidHomeSettings EMPTY = new MaidHomeSettings(
        false,
        false,
        0,
        Point.ZERO,
        Point.ZERO,
        Point.ZERO);
    public final boolean enabled, configured;
    public final int dimension;
    public final Point work, idle, rest;

    public MaidHomeSettings(boolean enabled, boolean configured, int dimension, Point work, Point idle, Point rest) {
        this.enabled = enabled;
        this.configured = configured;
        this.dimension = dimension;
        this.work = Objects.requireNonNull(work, "work");
        this.idle = Objects.requireNonNull(idle, "idle");
        this.rest = Objects.requireNonNull(rest, "rest");
    }

    public Point center(Activity activity) {
        return activity == Activity.WORK ? work : activity == Activity.IDLE ? idle : rest;
    }

    public String encode() {
        return "1;" + (enabled ? 1
            : 0) + ";" + (configured ? 1 : 0) + ";" + dimension + ";" + work + ";" + idle + ";" + rest;
    }

    public static MaidHomeSettings decode(String encoded) {
        if (encoded == null || encoded.length() > 192) throw new IllegalArgumentException("Invalid home encoding");
        var parts = encoded.split(";", -1);
        if (parts.length != 7 || !"1".equals(parts[0]) || !bit(parts[1]) || !bit(parts[2]))
            throw new IllegalArgumentException("Invalid home encoding");
        return new MaidHomeSettings(
            "1".equals(parts[1]),
            "1".equals(parts[2]),
            Integer.parseInt(parts[3]),
            Point.decode(parts[4]),
            Point.decode(parts[5]),
            Point.decode(parts[6]));
    }

    private static boolean bit(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    public static final class Point {

        public static final Point ZERO = new Point(0, 0, 0);
        public final int x, y, z;

        public Point(int x, int y, int z) {
            if (Math.abs((long) x) > 29999984 || Math.abs((long) z) > 29999984 || y < 0 || y > 255)
                throw new IllegalArgumentException("Home center outside build bounds");
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean containsBlock(int px, int py, int pz, int radius) {
            double dx = (double) px - x, dy = (double) py - y, dz = (double) pz - z;
            return radius > 0 && dx * dx + dy * dy + dz * dz < (double) radius * radius;
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }

        private static Point decode(String encoded) {
            var values = encoded.split(",", -1);
            if (values.length != 3) throw new IllegalArgumentException("Invalid home center");
            return new Point(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]));
        }
    }
}
