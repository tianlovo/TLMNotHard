package com.tianluoqaq.tlmnh.common.life.hunger;

/** 女仆饥饿状态机与旧档迁移 NBT 键名定义 */
public final class MaidHungerNbtKeys {

    public static final String ROOT_TAG = "TLMNHHungerState";
    public static final String LEGACY_HUNGER = "MaidHunger";
    public static final String LEGACY_HUNGER_BACKUP = "LegacyMaidHunger";
    public static final String VERSION = "Version";
    public static final String FOOD = "Food";
    public static final String SATURATION = "Saturation";
    public static final String EXHAUSTION = "Exhaustion";
    public static final String REGEN_TICKS = "RegenTicks";
    public static final String STARVE_TICKS = "StarveTicks";

    private MaidHungerNbtKeys() {}
}
