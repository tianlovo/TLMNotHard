package com.tianluoqaq.tlmnh.common.combat.weapon;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 只接纳经过执行核查的精确物品；攻击属性本身不代表外部武器已受支持 */
public final class MaidWeaponCatalog {

    private static final MaidWeaponStrike.HitEffects VANILLA_EFFECTS = new MaidWeaponStrike.HitEffects() {

        @Override
        public DamageSource damageSource(EntityMaid maid, EntityLivingBase target) {
            return DamageSource.causeMobDamage(maid);
        }

        @Override
        public void settleAcceptedHit(ItemStack original, EntityLivingBase target, EntityMaid maid) {
            original.getItem()
                .hitEntity(original, target, maid);
        }
    };

    private MaidWeaponCatalog() {}

    /** 只在复制栈上提取能力，返回值可以交给不接触世界的规划器 */
    public static MaidWeaponProfile inspect(ItemStack stack) {
        if (stack == null || stack.getItem() == null
            || stack.stackSize != WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD)
            return MaidWeaponProfile.unknown(WeaponCombatIds.ID_MISSING_OR_INVALID_STACK);
        Item item = stack.getItem();
        if (CompatServices.weapons()
            .handles(item))
            return CompatServices.weapons()
                .inspect(stack);
        if (!vanillaSword(item) && item != Items.bow)
            return MaidWeaponProfile.unknown(WeaponCombatIds.ID_WEAPON_NOT_ADMITTED);
        if (!IntegrationGuard.available(Side.SERVER, WeaponCombatIds.VANILLA_GUARD))
            return MaidWeaponProfile.unknown(WeaponCombatIds.ID_VANILLA_CAPABILITY_DISABLED);
        try {
            if (item == Items.bow ? item.getClass() != ItemBow.class : item.getClass() != ItemSword.class)
                return MaidWeaponProfile.unknown(WeaponCombatIds.ID_REPLACED_VANILLA_ITEM_CLASS);
            var before = stack.copy();
            var copy = stack.copy();
            int damage = copy.getItemDamage(), maximum = copy.getMaxDamage();
            if (damage < 0 || damage > maximum)
                return MaidWeaponProfile.unknown(WeaponCombatIds.ID_INVALID_VANILLA_DURABILITY);
            var profile = describe(
                item == Items.bow ? MaidWeaponProfile.Kind.BOW : MaidWeaponProfile.Kind.MELEE,
                item == Items.bow ? MaidWeaponProfile.Family.VANILLA_BOW : MaidWeaponProfile.Family.VANILLA_SWORD,
                WeaponCombatIds.VANILLA_DEPENDENCY,
                copy,
                WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE,
                WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD,
                Math.max(WeaponCombatTuning.WEAPON_CATALOG_MAX_FIRST_LIMIT, (long) maximum + 1 - damage),
                WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD);
            if (!ItemStack.areItemStacksEqual(stack, before))
                throw new IllegalStateException(WeaponCombatDiagnostics.MSG_WEAPON_INSPECTION_CHANGED_ORIGINAL_STACK);
            return profile;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, WeaponCombatIds.VANILLA_GUARD, failure);
            return MaidWeaponProfile.unknown(WeaponCombatIds.ID_VANILLA_CAPABILITY_FAILURE);
        }
    }

    /** 必须先准备，再由实体装备阶段确认；此方法不会代替原生装备属性刷新 */
    public static MaidWeaponStrike prepareMelee(EntityMaid maid, ItemStack stack) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || stack == null
            || stack.getItem() == null
            || maid.getHeldItem() != stack) return null;
        if (CompatServices.weapons()
            .handles(stack.getItem()))
            return CompatServices.weapons()
                .prepareMelee(maid, stack);
        var profile = inspect(stack);
        if (!profile.melee() || maid.getHeldItem() != stack) return null;
        return new MaidWeaponStrike(stack, profile, false, VANILLA_EFFECTS);
    }

    /** 只供已核查的提供方在其复制栈上构造能力快照 */
    public static MaidWeaponProfile describe(MaidWeaponProfile.Kind kind, MaidWeaponProfile.Family family,
        String dependency, ItemStack normalizedCopy, float physicalBonus, long durabilityCost, long remainingDurability,
        int hurtResistanceMultiplier) {
        float estimate = kind == MaidWeaponProfile.Kind.MELEE ? estimatePhysical(normalizedCopy, physicalBonus)
            : WeaponCombatTuning.WEAPON_CATALOG_INSPECT_ZERO_BASE;
        return new MaidWeaponProfile(
            kind,
            family,
            WeaponCombatIds.ID_SUPPORTED,
            dependency,
            physicalBonus,
            kind == MaidWeaponProfile.Kind.MELEE ? WeaponCombatTuning.FALLING_CRITICAL_MULTIPLIER
                : WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD,
            estimate,
            durabilityCost,
            remainingDurability,
            hurtResistanceMultiplier,
            level(normalizedCopy, Enchantment.sharpness),
            level(normalizedCopy, Enchantment.smite),
            level(normalizedCopy, Enchantment.baneOfArthropods),
            level(normalizedCopy, Enchantment.knockback),
            level(normalizedCopy, Enchantment.fireAspect),
            level(normalizedCopy, Enchantment.power),
            level(normalizedCopy, Enchantment.punch),
            level(normalizedCopy, Enchantment.infinity) > 0,
            level(normalizedCopy, Enchantment.flame) > 0);
    }

    private static boolean vanillaSword(Item item) {
        return item == Items.wooden_sword || item == Items.stone_sword
            || item == Items.iron_sword
            || item == Items.golden_sword
            || item == Items.diamond_sword;
    }

    private static int level(ItemStack stack, Enchantment enchantment) {
        return EnchantmentHelper.getEnchantmentLevel(enchantment.effectId, stack);
    }

    private static float estimatePhysical(ItemStack copy, float bonus) {
        var modifiers = copy.getAttributeModifiers()
            .get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
        double base = WeaponCombatTuning.MAID_BASE_ATTACK_DAMAGE;
        for (Object entry : modifiers) {
            var modifier = (AttributeModifier) entry;
            if (modifier.getOperation() == 0) base += modifier.getAmount();
        }
        double result = base;
        for (Object entry : modifiers) {
            var modifier = (AttributeModifier) entry;
            if (modifier.getOperation() == WeaponCombatTuning.TARGET_STACK_SIZE_THRESHOLD)
                result += base * modifier.getAmount();
        }
        for (Object entry : modifiers) {
            var modifier = (AttributeModifier) entry;
            if (modifier.getOperation() == WeaponCombatTuning.TARGET_GET_OPERATION_THRESHOLD)
                result *= WeaponCombatTuning.WEAPON_CATALOG_ESTIMATE_PHYSICAL_FACTOR + modifier.getAmount();
        }
        result += bonus;
        if (!Double.isFinite(result) || result < 0 || result > Float.MAX_VALUE)
            throw new IllegalStateException(WeaponCombatDiagnostics.MSG_INVALID_WEAPON_PHYSICAL_ESTIMATE);
        return (float) result;
    }
}
