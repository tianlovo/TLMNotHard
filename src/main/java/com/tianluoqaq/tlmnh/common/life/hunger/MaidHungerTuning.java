package com.tianluoqaq.tlmnh.common.life.hunger;

/** 女仆饥饿状态机数值边界、代谢周期与动作耗竭常量 */
public final class MaidHungerTuning {

    public static final int SCHEMA_VERSION = 1;
    public static final int NBT_TAG_INT = 3;
    public static final int NBT_TAG_FLOAT = 5;
    public static final int NBT_TAG_COMPOUND = 10;

    public static final int ZERO_TICKS = 0;
    public static final int MIN_FOOD = 0;
    public static final int MIN_PERIOD_TICKS = 1;
    public static final float ZERO_FLOAT = 0.0F;

    public static final int MAX_FOOD = 20;
    public static final float INITIAL_SATURATION = 5.0F;
    public static final float MAX_EXHAUSTION = 40.0F;
    public static final float EXHAUSTION_STEP = 4.0F;
    public static final float SATURATION_DRAIN_UNIT = 1.0F;
    public static final float SATURATION_ADD_MULTIPLIER = 2.0F;

    public static final int VANILLA_MIN_HUNGER_TO_HEAL = 18;
    public static final int VANILLA_REGEN_PERIOD_TICKS = 80;
    public static final int STARVE_PERIOD_TICKS = 80;
    public static final float NATURAL_HEAL_AMOUNT = 1.0F;
    public static final float VANILLA_HEAL_EXHAUSTION = 3.0F;

    public static final float WALK_EXHAUSTION_PER_BLOCK = 0.01F;
    public static final float SPRINT_EXHAUSTION_PER_BLOCK = 0.1F;
    public static final float SWIM_EXHAUSTION_PER_BLOCK = 0.015F;
    public static final float JUMP_EXHAUSTION = 0.2F;
    public static final float SPRINT_JUMP_EXHAUSTION = 0.8F;
    public static final float MELEE_HIT_EXHAUSTION = 0.3F;

    private MaidHungerTuning() {}
}
