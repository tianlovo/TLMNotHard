package com.tianluoqaq.tlmnh.common.life.hazard.spi;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/** 格雷与 GT++ 热锭热害数值识别、全套热防护判定与真实热伤害提交窄接口 */
public interface HotItemHazardCompatProvider {

    HotItemHazardCompatProvider NOOP = new HotItemHazardCompatProvider() {};

    default boolean isAvailable() {
        return false;
    }

    default float inspectItemHeatDamage(ItemStack stack) {
        return HotHazardSpiTuning.NO_HEAT_DAMAGE;
    }

    default float perTickItemHeatDamage(ItemStack stack) {
        return HotHazardSpiTuning.NO_HEAT_DAMAGE;
    }

    default boolean hasFullHeatProtection(EntityLivingBase maid) {
        return false;
    }

    default boolean applyHotItemDamage(EntityLivingBase maid, float heatDamage, ItemStack hotStack) {
        return false;
    }
}
