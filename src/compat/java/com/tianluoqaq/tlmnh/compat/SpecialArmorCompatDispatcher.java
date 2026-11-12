package com.tianluoqaq.tlmnh.compat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.compat.gravisuite.common.armor.GraviSuiteSpecialArmorCompat;
import com.tianluoqaq.tlmnh.compat.ic2.common.armor.Ic2SpecialArmorCompat;

/** 独立提供方的护甲特殊效果分发，防御结算仍使用 Forge 原生接口 */
public final class SpecialArmorCompatDispatcher implements SpecialArmorCompatProvider {

    public static final SpecialArmorCompatDispatcher INSTANCE = new SpecialArmorCompatDispatcher();

    private SpecialArmorCompatDispatcher() {}

    @Override
    public boolean tickSpecialArmor(EntityLivingBase maid, ItemStack boots, ItemStack legs, ItemStack chest,
        ItemStack helmet, ArmorSupportAccess access) {
        boolean changed = Ic2SpecialArmorCompat.INSTANCE.tickSpecialArmor(maid, boots, legs, chest, helmet, access);
        return GraviSuiteSpecialArmorCompat.INSTANCE.tickSpecialArmor(maid, boots, legs, chest, helmet, access)
            || changed;
    }

    @Override
    public boolean canBootsFullyAbsorbFall(EntityLivingBase maid, ItemStack boots, float distance) {
        return Ic2SpecialArmorCompat.INSTANCE.canBootsFullyAbsorbFall(maid, boots, distance);
    }
}
