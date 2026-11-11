package com.tianluoqaq.tlmnh.common.life;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 一条原生 DataWatcher 字符串同步完整家园设置，避免分字段更新时混合新旧活动点 */
public final class MaidHomeSettings {

    public static final MaidHomeSettings EMPTY = new MaidHomeSettings(
        false,
        false,
        MaidLifeTuning.DECODE_INITIAL_VALUE,
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
        this.work = Objects.requireNonNull(work, MaidLifeIds.ID_WORK);
        this.idle = Objects.requireNonNull(idle, MaidLifeIds.ID_IDLE);
        this.rest = Objects.requireNonNull(rest, MaidLifeIds.ID_REST);
    }

    public Point center(Activity activity) {
        return activity == Activity.WORK ? work : activity == Activity.IDLE ? idle : rest;
    }

    public String encode() {
        return MaidLifeIds.ID_1
            + (enabled ? MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP : MaidLifeTuning.DECODE_INITIAL_VALUE)
            + MaidLifeIds.SEMICOLON_DELIMITER
            + (configured ? MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP : MaidLifeTuning.DECODE_INITIAL_VALUE)
            + MaidLifeIds.SEMICOLON_DELIMITER
            + dimension
            + MaidLifeIds.SEMICOLON_DELIMITER
            + work
            + MaidLifeIds.SEMICOLON_DELIMITER
            + idle
            + MaidLifeIds.SEMICOLON_DELIMITER
            + rest;
    }

    public static MaidHomeSettings decode(String encoded) {
        if (encoded == null || encoded.length() > MaidLifeTuning.MAX_LENGTH_THRESHOLD)
            throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_HOME_ENCODING);
        var parts = encoded.split(MaidLifeIds.SEMICOLON_DELIMITER, MaidLifeTuning.UNSET_OR_NOT_FOUND);
        if (parts.length != MaidLifeTuning.TARGET_LENGTH_THRESHOLD_2
            || !MaidLifeIds.ID_1_2.equals(parts[MaidLifeTuning.DECODE_INITIAL_VALUE])
            || !bit(parts[MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP])
            || !bit(parts[MaidLifeTuning.HOME_AREA_PARSE_INT_FIRST_PARAM]))
            throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_HOME_ENCODING);
        return new MaidHomeSettings(
            MaidLifeIds.ID_1_2.equals(parts[MaidLifeTuning.HOME_AREA_SETTINGS_UNIT_STEP]),
            MaidLifeIds.ID_1_2.equals(parts[MaidLifeTuning.HOME_AREA_PARSE_INT_FIRST_PARAM]),
            Integer.parseInt(parts[MaidLifeTuning.TARGET_LENGTH_THRESHOLD]),
            Point.decode(parts[MaidLifeTuning.HOME_SETTINGS_DECODE_FIRST_PARAM]),
            Point.decode(parts[MaidLifeTuning.HOME_SETTINGS_DECODE_FIRST_PARAM_2]),
            Point.decode(parts[MaidLifeTuning.HOME_SETTINGS_DECODE_FIRST_PARAM_3]));
    }

    private static boolean bit(String value) {
        return MaidLifeIds.ID_0.equals(value) || MaidLifeIds.ID_1_2.equals(value);
    }

    public static final class Point {

        public static final Point ZERO = new Point(
            MaidLifeTuning.DECODE_INITIAL_VALUE,
            MaidLifeTuning.DECODE_INITIAL_VALUE,
            MaidLifeTuning.DECODE_INITIAL_VALUE);
        public final int x, y, z;

        public Point(int x, int y, int z) {
            if (Math.abs((long) x) > MaidLifeTuning.HOME_AREA_CAN_EDIT_PARAM
                || Math.abs((long) z) > MaidLifeTuning.HOME_AREA_CAN_EDIT_PARAM
                || y < 0
                || y > MaidLifeTuning.MAX_Y_THRESHOLD)
                throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_HOME_CENTER_OUTSIDE_BUILD_BOUNDS);
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
            return x + MaidLifeIds.COMMA_DELIMITER + y + MaidLifeIds.COMMA_DELIMITER + z;
        }

        private static Point decode(String encoded) {
            var values = encoded.split(MaidLifeIds.COMMA_DELIMITER, MaidLifeTuning.UNSET_OR_NOT_FOUND);
            if (values.length != MaidLifeTuning.TARGET_LENGTH_THRESHOLD)
                throw new IllegalArgumentException(MaidLifeDiagnostics.MSG_INVALID_HOME_CENTER);
            return new Point(
                Integer.parseInt(values[MaidLifeTuning.HOME_POSITION_X_INDEX]),
                Integer.parseInt(values[MaidLifeTuning.HOME_POSITION_Y_INDEX]),
                Integer.parseInt(values[MaidLifeTuning.HOME_POSITION_Z_INDEX]));
        }
    }
}
