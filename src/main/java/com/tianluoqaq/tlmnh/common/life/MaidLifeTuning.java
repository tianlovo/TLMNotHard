package com.tianluoqaq.tlmnh.common.life;

/** 女仆日常进食、好感与恢复规则常量（Tuning） */
public final class MaidLifeTuning {

    public static final int HOME_POSITION_Z_INDEX = 2;

    public static final int HOME_POSITION_Y_INDEX = 1;

    public static final int HOME_POSITION_X_INDEX = 0;

    public static final int DATA_HOME = 29;
    public static final int SNACK_INTERVAL = 45 * 20;
    public static final int REWARD_INTERVAL = 3 * 60 * 20;
    public static final double SNACK_OWNER_RANGE_SQ = 8 * 8;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int DECODE_INITIAL_VALUE = 0;
    public static final int TARGET_LENGTH_THRESHOLD = 3;
    public static final int HOME_AREA_SETTINGS_UNIT_STEP = 1;
    public static final int HOME_AREA_PARSE_INT_FIRST_PARAM = 2;
    public static final int MAX_RESTRICTION_RADIUS_THRESHOLD = 64;
    public static final int HOME_AREA_CAN_EDIT_PARAM = 29999984;
    public static final int MIN_POS_Y_THRESHOLD = 256;
    public static final int MAX_LENGTH_THRESHOLD = 192;
    public static final int TARGET_LENGTH_THRESHOLD_2 = 7;
    public static final int HOME_SETTINGS_DECODE_FIRST_PARAM = 4;
    public static final int HOME_SETTINGS_DECODE_FIRST_PARAM_2 = 5;
    public static final int HOME_SETTINGS_DECODE_FIRST_PARAM_3 = 6;
    public static final int MAX_Y_THRESHOLD = 255;
    public static final long LIFE_RULES_FLOOR_MOD_SECOND_LIMIT = 24000L;
    public static final int MIN_TIME_THRESHOLD = 8000;
    public static final int MIN_TIME_THRESHOLD_2 = 12000;
    public static final int MIN_TIME_THRESHOLD_3 = 16000;

    private MaidLifeTuning() {}
}
