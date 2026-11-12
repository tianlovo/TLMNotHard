package com.tianluoqaq.tlmnh.common.life.armor.spi;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/** IC2 纳米套、量子套与全套防化服服务端特殊生存效果及高坠免伤预判窄接口 */
public interface SpecialArmorCompatProvider {

    SpecialArmorCompatProvider NOOP = new SpecialArmorCompatProvider() {};

    interface ArmorSupportAccess {

        ItemStack extractMatching(java.util.function.Predicate<ItemStack> filter, int count, boolean simulate);

        int currentFood();

        void addFoodStats(int food, float saturationModifier);

        int maidRawSlotCount();

        ItemStack getMaidRawSlot(int slot);

        void setMaidRawSlot(int slot, ItemStack stack);

        ItemStack insertIntoMaidInventory(ItemStack stack, boolean simulate);

        boolean isGroundFastMoving();

        void applyQuantumLegSpeedBoost();
    }

    default boolean tickSpecialArmor(EntityLivingBase maid, ItemStack boots, ItemStack legs, ItemStack chest,
        ItemStack helmet, ArmorSupportAccess access) {
        return false;
    }

    default boolean canBootsFullyAbsorbFall(EntityLivingBase maid, ItemStack boots, float fallDistance) {
        return false;
    }
}
