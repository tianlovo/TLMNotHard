package com.tianluoqaq.tlmnh.compat.ic2.common.flight;

import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider.FlightCapability;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.ic2.Ic2Compat;
import com.tianluoqaq.tlmnh.compat.ic2.Ic2Ids;

import cpw.mods.fml.relauncher.Side;

/** IC2 喷气背包零耗电、零耗燃油飞行识别 */
public final class Ic2FlightCompat {

    private Ic2FlightCompat() {}

    public static boolean isStandaloneJetpack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(Ic2Compat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, Ic2Ids.FLIGHT_GUARD)) {
            return false;
        }
        Class<?> cls = stack.getItem()
            .getClass();
        while (cls != null && cls != Object.class) {
            String name = cls.getName();
            if (Ic2FlightReflectionSpec.JETPACK_ELECTRIC_CLASS.equals(name)
                || Ic2FlightReflectionSpec.JETPACK_CLASS.equals(name)) {
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
        if (stack == null || stack.stackSize <= 0
            || !(stack.getItem() instanceof ItemArmor armor)
            || armor.armorType != Ic2FlightReflectionSpec.CHEST_ARMOR_TYPE) return FlightCapability.NONE;
        if (!OptionalApi.isModLoaded(Ic2Compat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, Ic2Ids.FLIGHT_GUARD)) {
            return FlightCapability.NONE;
        }
        try {
            Class<?> cls = stack.getItem()
                .getClass();
            while (cls != null && cls != Object.class) {
                String name = cls.getName();
                if (Ic2FlightReflectionSpec.JETPACK_ELECTRIC_CLASS.equals(name)
                    || Ic2FlightReflectionSpec.JETPACK_CLASS.equals(name)) {
                    return FlightCapability.SPECIAL_FLIGHT;
                }
                if (Ic2FlightReflectionSpec.QUANTUM_SUIT_CLASS.equals(name)) {
                    NBTTagCompound tag = stack.getTagCompound();
                    if (tag != null && tag.getBoolean(Ic2FlightReflectionSpec.NBT_EMBEDDED_JETPACK)) {
                        return FlightCapability.SPECIAL_FLIGHT;
                    }
                }
                cls = cls.getSuperclass();
            }
            return FlightCapability.NONE;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, Ic2Ids.FLIGHT_GUARD, failure);
            return FlightCapability.NONE;
        }
    }
}
