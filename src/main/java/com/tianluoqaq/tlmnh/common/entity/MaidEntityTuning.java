package com.tianluoqaq.tlmnh.common.entity;

/** 女仆实体属性、姿态与任务基础常量（Tuning） */
public final class MaidEntityTuning {

    public static final int CURRENT_VERSION = 1;
    public static final byte STATUS = 47;
    public static final long DURATION_MILLIS = 1750;
    public static final float ENTITY_POSE_STANDING_FIRST_FACTOR = .6F;
    public static final float ENTITY_POSE_STANDING_SECOND_FACTOR = 1.5F;
    public static final float ENTITY_POSE_SLEEPING_FIRST_FACTOR = .2F;
    public static final int SLEEPING_SINGLE_UNIT = 1;
    public static final int POSE_BEGGING_FIRST_PARAM = 2;
    public static final int POSE_USING_ITEM_FIRST_PARAM = 4;
    public static final int POSE_USING_OFFHAND_FIRST_PARAM = 8;
    public static final int POSE_SWING_OFFHAND_FIRST_PARAM = 16;
    public static final int POSE_AIMING_BOW_FIRST_PARAM = 32;
    public static final int POSE_SWIMMING_FIRST_PARAM = 64;
    public static final int POSE_SWINGING_ARMS_FIRST_PARAM = 128;
    public static final double MIDPOINT_DIVISOR_D = 2.0;
    public static final float POSE_DIMENSIONS_MAX_FIRST_FACTOR = 0F;
    public static final double POSE_DIMENSIONS_REFRESH_DX_OFFSET = 1.0e-6;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final double TELEPORT_PARTICLES_FRACTION = 127.0D;
    public static final float TELEPORT_PARTICLES_SHOW_VX_OFFSET = .5F;
    public static final double TELEPORT_PARTICLES_SHOW_FACTOR = .5;

    private MaidEntityTuning() {}
}
