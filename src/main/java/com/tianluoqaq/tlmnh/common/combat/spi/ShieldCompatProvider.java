package com.tianluoqaq.tlmnh.common.combat.spi;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;

/** 盾牌与模块化破盾工具的窄适配契约；未安装对应模组时默认不识别任何外部盾牌 */
public interface ShieldCompatProvider {

    ShieldCompatProvider NOOP = new ShieldCompatProvider() {};

    interface ArrowCaptureHandle {

        NBTTagCompound applyTo(ItemStack stack);
    }

    default String defaultBlockSound() {
        return CombatSpiIds.ID_RANDOM_BREAK;
    }

    default Item previewShieldItem() {
        return null;
    }

    default boolean isShield(ItemStack stack) {
        return false;
    }

    default boolean supportsBaseShield(String itemId) {
        return false;
    }

    default boolean supportsModularShield(String itemId) {
        return false;
    }

    default MaidShieldItems.Status modularShieldStatus(ItemStack stack, String itemId) {
        return MaidShieldItems.Status.UNSUPPORTED;
    }

    default NBTTagCompound readToolData(ItemStack stack) {
        return null;
    }

    default boolean isToolBroken(ItemStack stack) {
        return false;
    }

    default boolean isModularTool(String itemId) {
        return false;
    }

    default boolean isUsableModularAxe(ItemStack stack) {
        return false;
    }

    default boolean canBlock(ItemStack stackCopy, DamageSource source) {
        return false;
    }

    default float blockAngle(ItemStack stackCopy) {
        return CombatSpiTuning.PROJECTILE_PHYSICS_ARROW_GRAVITY_FACTOR;
    }

    default float damageReduction(ItemStack stackCopy, DamageSource source) {
        return CombatSpiTuning.PROJECTILE_PHYSICS_ARROW_GRAVITY_FACTOR;
    }

    default boolean supportsArrowCapture(ItemStack stack) {
        return false;
    }

    default ArrowCaptureHandle prepareArrowCapture(ItemStack stack) {
        return null;
    }
}
