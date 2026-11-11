package com.tianluoqaq.tlmnh.common.lifecycle;

/** 女仆绑定、死亡、胶片与复活生命周期常量（Tuning） */
public final class MaidLifecycleTuning {

    public static final int MISSING_CREDENTIAL_SLOT = -1;

    public static final int MAX_ORBS_PER_PASS = 64;
    public static final int CORE_INITIAL_VALUE = 0;
    public static final int TARGET_STACK_SIZE_THRESHOLD = 1;
    public static final int TARGET_SIZE_THRESHOLD = 3;
    public static final int CAPTURE_RECORD_VALIDATE_PARAM = 8;
    public static final int CAPTURE_SERVICE_ADMITTED_PARAM = 64;
    public static final int CAPTURE_SERVICE_PUT_SECOND_PARAM = 20;
    public static final int DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM = 5;
    public static final int DEATH_INGRESS_STATE_PARAM = 2;
    public static final double DEATH_INGRESS_RUN_FACTOR = .5;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final double FILM_RECOVERY_MAX_FIRST_FACTOR = 0.1D;
    public static final int FILM_RECOVERY_DELAY_BEFORE_CAN_PICKUP = 10;
    public static final int UNSIGNED_BYTE_MASK = 255;
    public static final int MAX_SCHEMA_THRESHOLD = 9;
    public static final int MIN_SCHEMA_THRESHOLD = 6;
    public static final int MAX_SCHEMA_THRESHOLD_2 = 7;
    public static final int CHUNK_LOCAL_MASK = 15;
    public static final int UPPER_WORD_SHIFT = 32;
    public static final long UNSIGNED_INT_BIT_MASK = 0xffffffffL;
    public static final int LIFECYCLE_ON_PLAYER_TICK_PARAM = 100;
    public static final float MIDPOINT_DIVISOR_F = 2.0F;
    public static final float LIFECYCLE_SERVICE_HAS_HEALTH_FACTOR = 1.0F;
    public static final double CENTER_OFFSET_D = 0.5D;
    public static final double LIFECYCLE_SERVICE_SET_LOCATION_AND_ANGLES_SECOND_FAC = 1.0D;
    public static final double LIFECYCLE_SERVICE_SET_LOCATION_AND_ANGLES_FIRST_FACT = .5D;
    public static final double LIFECYCLE_SERVICE_SET_LOCATION_AND_ANGLES_SECOND_FAC_2 = 1D;
    public static final double LIFECYCLE_SERVICE_DOMAIN_FACTOR = 64.0D;
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final float REDEMPTION_DELIVERY_PAID_HEALTH_FACTOR = .25F;
    public static final float SHRINE_BLOCK_SET_RESISTANCE_FIRST_FACTOR = 3.0F;
    public static final float SHRINE_BLOCK_FACING = 4.0F;
    public static final float FULL_TURN_DEGREES_F = 360.0F;
    public static final double SHRINE_BLOCK_INSET = .125;
    public static final double SHRINE_BLOCK_LOW = 5.0;
    public static final double SHRINE_BLOCK_LOW_2 = 16.0;
    public static final float TOMBSTONE_ENTITY_SET_SIZE_FIRST_FACTOR = 0.8F;
    public static final float TOMBSTONE_ENTITY_SET_SIZE_SECOND_FACTOR = 1.2F;

    private MaidLifecycleTuning() {}
}
