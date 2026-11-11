package com.tianluoqaq.tlmnh.common.combat;

/** 战斗结算、装备纪元与弓箭射击参数（Tuning） */
public final class MaidCombatTuning {

    public static final int MISSING_AMMUNITION_SLOT = -1;

    public static final int VERSION = 1;
    public static final int MAX_MAIN_HAND_ATTRIBUTE_MODIFIERS = 16;
    public static final int MAX_RETIRED_ATTRIBUTE_MODIFIERS = 32;
    public static final int DRAW_TICKS = 20;
    public static final int MAX_FLIGHT_TICKS = 60;
    public static final double RANGE_SQ = 144;
    public static final int MAX_SINGLE_UNIT = 1;
    public static final int MAX_INITIAL_VALUE = 0;
    public static final int TARGET_SIZE_THRESHOLD = 2;
    public static final int EQUIPMENT_EPOCH_EQUIPMENT_PARAM = 6;
    public static final int MIN_SLOT_THRESHOLD = 5;
    public static final float BOW_ATTACK_DOMAIN_FACTOR = 1F;
    public static final int BOW_ATTACK_SPEND_SECOND_PARAM = 3;
    public static final float BOW_ATTACK_BALLISTIC_VECTOR_SECOND_FACTOR = .1F;
    public static final double BOW_ATTACK_BALLISTIC_VECTOR_SECOND_FACTOR_2 = .1;
    public static final double HALF_FACTOR_D = 0.5;
    public static final int BOW_ATTACK_SET_FIRE_FIRST_PARAM = 100;
    public static final double MIDPOINT_DIVISOR_D = 2.0;
    public static final float BOW_ATTACK_DOMAIN_FACTOR_2 = .4F;
    public static final float BOW_ATTACK_DOMAIN_FACTOR_3 = .8F;
    public static final double BOW_ATTACK_MIN_X = .3;
    public static final double BOW_ATTACK_MIN_Y = .5;
    public static final int CHUNK_SECTION_SHIFT = 4;
    public static final int BOW_ATTACK_RETRY_AT = 20;
    public static final float BOW_ATTACK_SET_THROWABLE_HEADING_FOURTH_FACTOR = 3F;
    public static final float BOW_ATTACK_SET_THROWABLE_HEADING_FIFTH_FACTOR = 0F;
    public static final int COMBAT_ATTRIBUTES_RANGED_ATTRIBUTE_FOURTH_PARAM = 1024;

    private MaidCombatTuning() {}
}
