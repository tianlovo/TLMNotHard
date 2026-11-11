package com.tianluoqaq.tlmnh.common.ai;

/** 女仆核心 AI 调度与恐慌控制参数（Tuning） */
public final class MaidAiTuning {

    public static final float MAX_SEARCH_RANGE = 16;
    public static final int OWNER_SCAN_INTERVAL = 10;
    public static final int PATH_INTERVAL = 10;
    public static final int FAILURE_RETRY_INTERVAL = 40;
    public static final int STUCK_INTERVAL = 40;
    public static final double START_DISTANCE_SQ = 25;
    public static final double STOP_DISTANCE_SQ = 9;
    public static final double MAX_DISTANCE_SQ = 4096;
    public static final double PROGRESS_DISTANCE_SQ = 0.0625;
    public static final double MOVED_TARGET_DISTANCE_SQ = 4;
    public static final int SCAN_INTERVAL = 20;
    public static final int PATH_RETRY = 20;
    public static final int ATTEMPTS = 10;
    public static final double DANGER_RANGE_SQ = 64;
    public static final double SPEED = .7;
    public static final int SCAN_INTERVAL_2 = 30;
    public static final int CANDIDATE_BUDGET = 64;
    public static final int PATH_CANDIDATES = 3;
    public static final int NAVIGATION_INITIAL_VALUE = 0;
    public static final float BOUNDED_PATH_PADDING = 8.0F;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final float CENTER_OFFSET_F = 0.5F;
    public static final float MIDPOINT_DIVISOR_F = 2.0F;
    public static final int BRAIN_COORDINATOR_SET_MUTEX_BITS_FIRST_PARAM = 7;
    public static final int MAX_PERSISTENT_READ_DEPTH_THRESHOLD = 1;
    public static final double HOME_CONTROLLER_DISTANCE = .5;
    public static final int MAX_DISTANCE_THRESHOLD = 9;
    public static final double CENTER_OFFSET_D = 0.5;
    public static final int HOME_CONTROLLER_TICK_PARAM = 20;
    public static final int HOME_CONTROLLER_NEXT_PATH = 40;
    public static final int HOME_CONTROLLER_MIN_SECOND_PARAM = 16;
    public static final double HOME_CONTROLLER_TICK_FACTOR = 0.7;
    public static final int MIN_ATTEMPT_THRESHOLD = 10;
    public static final int HOME_TELEPORT_TRY_RETURN_PARAM = 3;
    public static final float LEGACY_BRAIN_APPLY_MOVEMENT_FACTOR = .25F;
    public static final int HALF_TURN_DEGREES = 180;
    public static final double LEGACY_BRAIN_OFFSET_SECOND_FACTOR = -.6;
    public static final int LEGACY_BRAIN_EXPAND_SECOND_PARAM = 8;
    public static final int MAX_INSPECTED_THRESHOLD = 64;
    public static final int LEGACY_BRAIN_LOOK_AT_TARGET_PARAM = 30;
    public static final int LEGACY_BRAIN_ATTACK_INTERVAL_PARAM = 2;
    public static final int LEGACY_BRAIN_IGNORE_UNTIL = 100;
    public static final float LEGACY_BRAIN_SET_LOOK_POSITION_WITH_ENTITY_SECOND_FA = 10.0F;
    public static final double PICKUP_CONTROLLER_TICK_FACTOR = 0.25;
    public static final int PICKUP_CONTROLLER_EXPIRES = 120;
    public static final double PICKUP_CONTROLLER_CREATE_FIFTH_FACTOR = 0.6;

    private MaidAiTuning() {}
}
