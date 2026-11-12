package com.tianluoqaq.tlmnh.compat.adventurebackpack.common.flight;

import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider.FlightCapability;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.adventurebackpack.AdventureBackpackCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * AdventureBackpack 直升机背包（copterPack）与蒸汽喷气包（coalJetpack）适配：
 * 支持穿戴于胸甲槽或女仆饰品栏，零耗燃油、零耗煤炭；直升机背包浸水时不提供推进
 */
public final class AdventureBackpackFlightCompat {

    private AdventureBackpackFlightCompat() {}

    public static boolean isWearableFlightPack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(AdventureBackpackCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, AdventureBackpackFlightIds.GUARD)) {
            return false;
        }
        String className = stack.getItem()
            .getClass()
            .getName();
        return AdventureBackpackFlightReflectionSpec.COPTER_PACK_CLASS.equals(className)
            || AdventureBackpackFlightReflectionSpec.COAL_JETPACK_CLASS.equals(className);
    }

    public static FlightCapability inspect(ItemStack stack, boolean maidInWater) {
        if (!isWearableFlightPack(stack)) return FlightCapability.NONE;
        try {
            String className = stack.getItem()
                .getClass()
                .getName();
            if (AdventureBackpackFlightReflectionSpec.COPTER_PACK_CLASS.equals(className)) {
                return maidInWater ? FlightCapability.NONE : FlightCapability.SPECIAL_FLIGHT;
            }
            if (AdventureBackpackFlightReflectionSpec.COAL_JETPACK_CLASS.equals(className)) {
                return FlightCapability.SPECIAL_FLIGHT;
            }
            return FlightCapability.NONE;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, AdventureBackpackFlightIds.GUARD, failure);
            return FlightCapability.NONE;
        }
    }
}
