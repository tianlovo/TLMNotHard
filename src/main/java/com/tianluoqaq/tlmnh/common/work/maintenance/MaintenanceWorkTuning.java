package com.tianluoqaq.tlmnh.common.work.maintenance;

/** 维护仓扫描周期、检修触达距离、分段寻路步长与巡检驻留参数 */
public final class MaintenanceWorkTuning {

    public static final int ZERO = 0;
    public static final int ONE = 1;
    public static final int UNSET_Y = -1;
    public static final long UNSET_TIME = -1L;
    public static final int IDX_X = 0;
    public static final int IDX_Z = 1;
    public static final int CHECK_INTERVAL_TICKS = 20;
    public static final int SCAN_INTERVAL_TICKS = 40;
    public static final int PATROL_STAY_TICKS = 100;
    public static final int NAV_REFRESH_TICKS = 20;
    public static final int BLOCK_TO_CHUNK_SHIFT = 4;
    public static final int CHUNK_CENTER_OFFSET = 8;
    public static final int ANCHOR_SEARCH_VERTICAL_RANGE = 8;
    public static final int REPAIR_COOLDOWN_TICKS = 20;

    public static final double REPAIR_REACH_DIST_SQ = 12.25D;
    public static final double PATROL_ARRIVE_DIST_SQ = 6.25D;
    public static final double STEP_MAX_DISTANCE = 12.0D;
    public static final double WALK_SPEED = 0.6D;
    public static final double BLOCK_CENTER_OFFSET = 0.5D;
    public static final float LOOK_SPEED = 30.0F;
    public static final double WORK_UTILITY = 1.0D;

    private MaintenanceWorkTuning() {}
}
