/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/bauble/IMaidBauble.java, MIT
 * Adaptation: Minecraft 1.7.10 entity, damage and mutable injury amount
 */
package com.github.tartaricacid.touhoulittlemaid.api.bauble;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public interface IMaidBauble {

    default void onTick(EntityMaid maid, ItemStack stack) {}

    default void onInjured(EntityMaid maid, ItemStack stack, DamageSource source, float[] amount) {}

    default void onDeath(EntityMaid maid, ItemStack stack, DamageSource source) {}

    default void onPutOn(EntityMaid maid, ItemStack stack) {}

    default void onTakeOff(EntityMaid maid, ItemStack stack) {}

    default void onMeleeAttack(EntityMaid maid, ItemStack stack, Entity target) {}

    default void onFavorabilityLevelChange(EntityMaid maid, ItemStack stack, int oldLevel, int newLevel) {}

    default void syncClient(EntityMaid maid, ItemStack stack) {}
}
