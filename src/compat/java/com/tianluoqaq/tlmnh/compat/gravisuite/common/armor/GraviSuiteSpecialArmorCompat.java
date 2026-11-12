package com.tianluoqaq.tlmnh.compat.gravisuite.common.armor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;

import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.gravisuite.GraviSuiteCompat;

import cpw.mods.fml.relauncher.Side;

/** 只移植实体可用的胸甲灭火结算，飞行推进独立保持零耗能 */
public final class GraviSuiteSpecialArmorCompat implements SpecialArmorCompatProvider {

    public static final GraviSuiteSpecialArmorCompat INSTANCE = new GraviSuiteSpecialArmorCompat();
    private static volatile Api cached;

    private GraviSuiteSpecialArmorCompat() {}

    @Override
    public boolean tickSpecialArmor(EntityLivingBase maid, ItemStack boots, ItemStack legs, ItemStack chest,
        ItemStack helmet, ArmorSupportAccess access) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || chest == null
            || chest.getItem() == null
            || !maid.isBurning()
            || maid.isPotionActive(Potion.fireResistance)
            || !OptionalApi.isModLoaded(GraviSuiteCompat.MOD_ID)
            || !IntegrationGuard.available(Side.SERVER, GraviSuiteArmorReflectionSpec.GUARD)) return false;
        String name = chest.getItem()
            .getClass()
            .getName();
        double cost;
        if (GraviSuiteArmorReflectionSpec.ADVANCED_NANO.equals(name)) {
            if (maid.ticksExisted % GraviSuiteArmorReflectionSpec.NANO_PERIOD != 0) return false;
            cost = GraviSuiteArmorReflectionSpec.NANO_EXTINGUISH_COST;
        } else if (GraviSuiteArmorReflectionSpec.GRAVITY_CHEST.equals(name)) {
            if (!OptionalApi.isModLoaded(GraviSuiteArmorReflectionSpec.NEO_MOD_ID)) {
                maid.extinguish();
                return false;
            }
            cost = GraviSuiteArmorReflectionSpec.GRAVITY_EXTINGUISH_COST;
        } else return false;
        try {
            Api api = api();
            Object manager = api.manager.invoke();
            if (!(boolean) api.canUse.invoke(manager, chest, cost)) return false;
            double paid = (double) api.discharge.invoke(manager, chest, cost, Integer.MAX_VALUE, true, false, false);
            if (paid < cost) return paid > 0;
            maid.extinguish();
            return true;
        } catch (Throwable failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, GraviSuiteArmorReflectionSpec.GUARD, failure);
            return false;
        }
    }

    private static Api api() throws ReflectiveOperationException {
        if (cached != null) return cached;
        synchronized (GraviSuiteSpecialArmorCompat.class) {
            if (cached == null) {
                Class<?> manager = Class.forName(GraviSuiteArmorReflectionSpec.MANAGER_INTERFACE);
                var lookup = MethodHandles.publicLookup();
                cached = new Api(
                    lookup.findStaticGetter(
                        Class.forName(GraviSuiteArmorReflectionSpec.ELECTRIC_ITEM),
                        GraviSuiteArmorReflectionSpec.MANAGER_FIELD,
                        manager),
                    lookup.findVirtual(
                        manager,
                        GraviSuiteArmorReflectionSpec.CAN_USE,
                        MethodType.methodType(boolean.class, ItemStack.class, double.class)),
                    lookup.findVirtual(
                        manager,
                        GraviSuiteArmorReflectionSpec.DISCHARGE,
                        MethodType.methodType(
                            double.class,
                            ItemStack.class,
                            double.class,
                            int.class,
                            boolean.class,
                            boolean.class,
                            boolean.class)));
            }
            return cached;
        }
    }

    private static final class Api {

        final MethodHandle manager, canUse, discharge;

        Api(MethodHandle manager, MethodHandle canUse, MethodHandle discharge) {
            this.manager = manager;
            this.canUse = canUse;
            this.discharge = discharge;
        }
    }
}
