package com.tianluoqaq.tlmnh.compat.ic2.common.armor;

/** IC2 纳米套与量子套服务端生存效果耗电与阈值常量 */
public final class Ic2ArmorTuning {

    public static final int LOW_AIR_THRESHOLD = 100;
    public static final int AIR_REFILL_AMOUNT = 200;
    public static final int MAX_AIR_SUPPLY = 300;
    public static final double AIR_REFILL_EU_COST = 1000.0D;

    public static final double POISON_EU_BASE = 10000.0D;
    public static final double RADIATION_EU_BASE = 10000.0D;
    public static final double WITHER_EU_BASE = 25000.0D;

    public static final int FULL_HUNGER_LEVEL = 20;
    public static final double TIN_CAN_EU_COST = 1000.0D;
    public static final float TIN_CAN_SATURATION_FACTOR = 1.0F;

    public static final double NIGHTVISION_EU_COST = 1.0D;
    public static final int NIGHTVISION_DURATION_TICKS = 300;
    public static final int HAZMAT_FIRE_RES_TICKS = 40;
    public static final int LEVEL_ZERO_AMPLIFIER = 0;

    public static final int QUANTUM_LEGS_DRAIN_PERIOD_TICKS = 10;
    public static final double QUANTUM_LEGS_EU_COST = 1000.0D;

    public static final float SAFE_FALL_FREE_BLOCKS = 3.0F;
    public static final float QUANTUM_BOOT_DAMAGE_DIVISOR = 2.0F;
    public static final double QUANTUM_ENERGY_PER_DAMAGE = 20000.0D;
    public static final float NANO_BOOT_MAX_SAFE_FALL = 9.0F;
    public static final double NANO_ENERGY_PER_DAMAGE = 5000.0D;

    public static final int QUANTUM_FREE_FALL_BLOCKS = 10;
    public static final int NANO_FULL_FALL_DAMAGE_LIMIT = 8;
    public static final float RETURN_DROP_HEIGHT = 0.2F;

    public static final int NIGHTVISION_BRIGHT_LIGHT = 8;
    public static final int NIGHTVISION_BLINDNESS_TICKS = 100;

    public static final int ZERO_COST_BLOCKS = 0;

    private Ic2ArmorTuning() {}
}
