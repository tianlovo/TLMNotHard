package com.tianluoqaq.tlmnh.common.life.flight.spi;

import net.minecraft.item.ItemStack;

/**
 * 直升机背包、喷气背包、进阶喷气背包与纳米重力胸甲飞行能力识别窄接口。
 * 严格遵循零耗电、零耗燃料约束，仅提供装备模式识别与饰品准入，不暴露任何扣电或扣油操作
 */
public interface FlightEquipmentCompatProvider {

    FlightEquipmentCompatProvider NOOP = new FlightEquipmentCompatProvider() {};

    enum FlightCapability {
        NONE,
        SPECIAL_FLIGHT,
        CREATIVE_FLIGHT
    }

    default FlightCapability inspectChestArmor(ItemStack chestStack, boolean maidInWater) {
        return FlightCapability.NONE;
    }

    default FlightCapability inspectBauble(ItemStack baubleStack, boolean maidInWater) {
        return FlightCapability.NONE;
    }

    default boolean isWearableFlightBauble(ItemStack stack) {
        return false;
    }
}
