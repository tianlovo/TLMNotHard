package com.tianluoqaq.tlmnh.compat.gravisuite.common.flight;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider.FlightCapability;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.gravisuite.GraviSuiteCompat;
import com.tianluoqaq.tlmnh.compat.gravisuite.GraviSuiteIds;

import cpw.mods.fml.relauncher.Side;

/** GraviSuite 进阶喷气背包、进阶纳米胸甲与重力胸甲零耗电飞行识别 */
public final class GraviSuiteFlightCompat {

    private GraviSuiteFlightCompat() {}

    public static boolean isStandaloneJetpack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(GraviSuiteCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, GraviSuiteIds.FLIGHT_GUARD)) {
            return false;
        }
        Class<?> cls = stack.getItem()
            .getClass();
        while (cls != null && cls != Object.class) {
            if (GraviSuiteFlightReflectionSpec.ADV_JETPACK_CLASS.equals(cls.getName())) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    public static FlightCapability inspectBauble(ItemStack stack) {
        return isStandaloneJetpack(stack) ? FlightCapability.SPECIAL_FLIGHT : FlightCapability.NONE;
    }

    public static FlightCapability inspectChest(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return FlightCapability.NONE;
        if (!OptionalApi.isModLoaded(GraviSuiteCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, GraviSuiteIds.FLIGHT_GUARD)) {
            return FlightCapability.NONE;
        }
        try {
            Class<?> cls = stack.getItem()
                .getClass();
            while (cls != null && cls != Object.class) {
                String name = cls.getName();
                if (GraviSuiteFlightReflectionSpec.GRAVI_CHEST_CLASS.equals(name)) {
                    return FlightCapability.CREATIVE_FLIGHT;
                }
                if (GraviSuiteFlightReflectionSpec.ADV_NANO_CHEST_CLASS.equals(name)
                    || GraviSuiteFlightReflectionSpec.ADV_JETPACK_CLASS.equals(name)) {
                    return FlightCapability.SPECIAL_FLIGHT;
                }
                cls = cls.getSuperclass();
            }
            return FlightCapability.NONE;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GraviSuiteIds.FLIGHT_GUARD, failure);
            return FlightCapability.NONE;
        }
    }
}
