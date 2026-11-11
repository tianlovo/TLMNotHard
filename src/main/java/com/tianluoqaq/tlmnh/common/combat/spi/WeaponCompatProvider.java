package com.tianluoqaq.tlmnh.common.combat.spi;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponStrike;

/** 外部近战与远程武器的能力审查契约；未命中已知适配实现时回退到原版武器判定 */
public interface WeaponCompatProvider {

    WeaponCompatProvider NOOP = new WeaponCompatProvider() {};

    default boolean handles(Item item) {
        return false;
    }

    default MaidWeaponProfile inspect(ItemStack stack) {
        return MaidWeaponProfile.unknown("weapon_not_admitted");
    }

    default MaidWeaponStrike prepareMelee(EntityMaid maid, ItemStack stack) {
        return null;
    }
}
