package com.tianluoqaq.tlmnh.common.ai.faithful.locomotion;

/** 原版对齐模式步态与转向控制参数（Tuning） */
public final class FaithfulLocomotionTuning {

    public static final int DOOR_ACTOR_HASH_CODE_PARAM = 31;
    public static final int ENTRY_SINGLE_UNIT = 1;
    public static final int RANDOM_GATE_ENTRY_THIRD_PARAM = 2;
    public static final float RANDOM_GATE_POW_SECOND_FACTOR = 1.0F;
    public static final int RANDOM_GATE_TRY_RANDOM_LOOK_SECOND_PARAM = 5;
    public static final int RANDOM_GATE_SAMPLE_RANDOM_WALK_SECOND_PARAM = 3;
    public static final float RANDOM_GATE_SET_VECTOR_WALK_TARGET_SECOND_FACTOR = .3F;
    public static final int SET_VECTOR_WALK_TARGET_INITIAL_VALUE = 0;
    public static final int RANDOM_GATE_DEADLINE = 30;

    private FaithfulLocomotionTuning() {}
}
