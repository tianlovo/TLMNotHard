package com.tianluoqaq.tlmnh.compat.gregtech.common.hazard;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.tianluoqaq.tlmnh.common.life.hazard.spi.HotItemHazardCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.gregtech.GregTechCompat;

import cpw.mods.fml.relauncher.Side;
import gregtech.api.hazards.HazardProtection;
import gregtech.api.util.GTUtility;

/** 格雷 5U 与 GT++ 热物品热害检测、全套防化服免疫判定与真实热伤害提交实现 */
public final class GregTechHotItemHazardCompat implements HotItemHazardCompatProvider {

    public static final GregTechHotItemHazardCompat INSTANCE = new GregTechHotItemHazardCompat();

    private GregTechHotItemHazardCompat() {}

    @Override
    public boolean isAvailable() {
        return OptionalApi.isModLoaded(GregTechCompat.MOD_ID)
            && IntegrationGuard.available(Side.SERVER, GregTechHotHazardIds.GUARD);
    }

    @Override
    public float inspectItemHeatDamage(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.stackSize <= 0 || stack.getItem() == null) {
            return GregTechHotHazardTuning.ZERO_HEAT_DAMAGE;
        }
        try {
            float gtDamage = GTUtility.getHeatDamageFromItem(stack);
            if (gtDamage > GregTechHotHazardTuning.ZERO_HEAT_DAMAGE) return gtDamage;
            if (isGtppHotIngotClass(
                stack.getItem()
                    .getClass())) {
                return GregTechHotHazardTuning.GTPP_DEFAULT_HOT_DAMAGE;
            }
            int[] oreIds = OreDictionary.getOreIDs(stack);
            for (int oreId : oreIds) {
                String oreName = OreDictionary.getOreName(oreId);
                if (oreName != null && oreName.startsWith(GregTechHotHazardIds.ORE_PREFIX_INGOT_HOT)) {
                    return GregTechHotHazardTuning.OREDICT_FALLBACK_HOT_DAMAGE;
                }
            }
            return GregTechHotHazardTuning.ZERO_HEAT_DAMAGE;
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechHotHazardIds.GUARD, failure);
            return GregTechHotHazardTuning.ZERO_HEAT_DAMAGE;
        }
    }

    @Override
    public float perTickItemHeatDamage(ItemStack stack) {
        return isAvailable() && stack != null
            && stack.stackSize > 0
            && stack.getItem() != null
            && isGtppHotIngotClass(
                stack.getItem()
                    .getClass()) ? GregTechHotHazardTuning.GTPP_DEFAULT_HOT_DAMAGE
                        : GregTechHotHazardTuning.ZERO_HEAT_DAMAGE;
    }

    @Override
    public boolean hasFullHeatProtection(EntityLivingBase maid) {
        if (!isAvailable() || maid == null) return false;
        try {
            return HazardProtection.isWearingFullHeatHazmat(maid);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechHotHazardIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean applyHotItemDamage(EntityLivingBase maid, float heatDamage, ItemStack hotStack) {
        if (!isAvailable() || maid == null || heatDamage <= GregTechHotHazardTuning.ZERO_HEAT_DAMAGE) return false;
        try {
            return GTUtility.applyHeatDamageFromItem(maid, heatDamage, hotStack);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, GregTechHotHazardIds.GUARD, failure);
            return false;
        }
    }

    private static boolean isGtppHotIngotClass(Class<?> cls) {
        Class<?> cursor = cls;
        while (cursor != null && cursor != Object.class) {
            if (GregTechHotHazardReflectionSpec.GTPP_HOT_INGOT_SUPER.equals(cursor.getName())) return true;
            cursor = cursor.getSuperclass();
        }
        return false;
    }
}
