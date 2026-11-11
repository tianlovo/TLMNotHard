package com.tianluoqaq.tlmnh.common.ai.navigation.model;

/** 寻路上下文、角度与节点模型参数（Tuning） */
public final class NavigationModelTuning {

    public static final int MOVEMENT_ANGLES_DOMAIN_PARAM = 257;
    public static final double MOVEMENT_ANGLES_ASIN_FIRST_FACTOR = 256.0;
    public static final double MOVEMENT_ANGLES_HALF = .5;
    public static final long MOVEMENT_ANGLES_LONG_BITS_TO_DOUBLE_FIRST_LIMIT = 6910469410427058090L;
    public static final int MOVEMENT_ANGLES_BIT_SHIFT_1 = 1;
    public static final double MOVEMENT_ANGLES_ATAN2_FACTOR = 1.5;
    public static final int MOVEMENT_ANGLES_ATAN2_PARAM = 6;
    public static final double MOVEMENT_ANGLES_ANGLE = .16666666666666666;
    public static final int MIDPOINT_DIVISOR = 2;
    public static final int NAVIGATION_ACTOR_HASH_CODE_PARAM = 31;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final int UNSIGNED_BYTE_MASK = 255;
    public static final int PATH_NODE_BIT_MASK_32767 = 32767;
    public static final int SECOND_BYTE_SHIFT = 8;
    public static final int ALPHA_CHANNEL_SHIFT = 24;
    public static final int PATH_NODE_KEY_ZERO_BASE = 0;
    public static final int PATH_NODE_KEY_PARAM = 32768;
    public static final int PATH_TYPE_DAMAGE_FIRE_FIRST_PARAM = 16;
    public static final int PATH_TYPE_BREACH_FIRST_PARAM = 4;

    private NavigationModelTuning() {}
}
