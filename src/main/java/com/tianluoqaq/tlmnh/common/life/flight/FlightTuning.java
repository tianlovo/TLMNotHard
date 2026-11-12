package com.tianluoqaq.tlmnh.common.life.flight;

/** 女仆飞行背包三维跟随、气垫悬停与零耗能物理推进参数 */
public final class FlightTuning {

    public static final int CHEST_EQUIPMENT_SLOT = 3;
    public static final int SPECTATOR_GAME_TYPE_ID = 3;
    public static final float ZERO_FALL_DISTANCE = 0.0F;
    public static final double ZERO_VELOCITY = 0.0D;

    public static final double HOVER_FALL_TRIGGER_VY = -0.04D;
    public static final double SPECIAL_HOVER_DRIFT_VY = -0.02D;
    public static final double SPECIAL_GRAVITY_COMPENSATION = 0.075D;
    public static final double SPECIAL_ASCEND_THRUST = 0.11D;
    public static final double SPECIAL_MAX_ASCEND_VY = 0.38D;
    public static final double SPECIAL_MAX_HORIZ_SPEED = 0.34D;

    public static final double CREATIVE_MAX_HORIZ_SPEED = 0.45D;
    public static final double CREATIVE_MAX_VERT_SPEED = 0.35D;
    public static final double CREATIVE_VELOCITY_LERP = 0.35D;

    public static final double OWNER_AIRBORNE_MIN_CLEARANCE = 1.5D;
    public static final double OWNER_VERTICAL_GAP_TRIGGER = 2.2D;
    public static final double OWNER_FOLLOW_TRIGGER_DIST_SQ = 16.0D;
    public static final double OWNER_TARGET_STANDOFF = 2.8D;
    public static final double MIN_DIRECTION_NORM = 0.001D;
    public static final float YAW_RAD_TO_DEG = 57.295776F;
    public static final float YAW_OFFSET_DEG = 90.0F;

    public static final float MANUAL_ACCELERATION = 0.02F;
    public static final double AIR_DRAG = 0.91D;
    public static final double QUANTUM_GROUND_SPEED_MULTIPLIER = 1.5D;

    private FlightTuning() {}
}
