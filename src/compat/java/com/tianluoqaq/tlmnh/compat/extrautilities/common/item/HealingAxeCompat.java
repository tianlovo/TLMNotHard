package com.tianluoqaq.tlmnh.compat.extrautilities.common.item;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.combat.spi.HealingAxeCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.extrautilities.ExtraUtilitiesCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * ExtraUtilities 治愈之斧（defoliageAxe）适配：
 * 副手持握每 40 ticks 恢复饥饿与饱和；主手近战按原字节码消耗饥饿度、转化僵尸村民或对亡灵造成 16 点伤害，零耐久损耗
 */
public final class HealingAxeCompat implements HealingAxeCompatProvider {

    public static final HealingAxeCompat INSTANCE = new HealingAxeCompat();

    private HealingAxeCompat() {}

    @Override
    public boolean isHealingAxe(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) return false;
        if (!OptionalApi.isModLoaded(ExtraUtilitiesCompat.MOD_ID)) return false;
        if (!IntegrationGuard.available(Side.SERVER, HealingAxeIds.GUARD)) return false;
        try {
            Item item = stack.getItem();
            if (!HealingAxeReflectionSpec.ITEM_CLASS.equals(
                item.getClass()
                    .getName())) {
                return false;
            }
            String regName = Item.itemRegistry.getNameForObject(item);
            return HealingAxeIds.REGISTRY_NAME.equals(regName);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, HealingAxeIds.GUARD, failure);
            return false;
        }
    }

    @Override
    public boolean tickOffhandPassiveHunger(World world, ItemStack offhandStack, HungerRestoreSink sink) {
        if (world == null || world.isRemote || sink == null || !isHealingAxe(offhandStack)) return false;
        if (world.getTotalWorldTime() % HealingAxeTuning.OFFHAND_REGEN_PERIOD_TICKS != 0L) return false;
        sink.addStats(HealingAxeTuning.OFFHAND_FOOD_GAIN, HealingAxeTuning.OFFHAND_SATURATION_MODIFIER);
        return true;
    }

    @Override
    public StrikeOutcome resolveMainhandStrike(ItemStack mainhandStack, Entity target) {
        if (!isHealingAxe(mainhandStack)) {
            return StrikeOutcome.NOT_APPLICABLE_OUTCOME;
        }
        if (target instanceof EntityLiving living && living.isEntityAlive() && living.isEntityUndead()) {
            if (living instanceof EntityZombie zombie && zombie.isVillager()) {
                return new StrikeOutcome(
                    StrikeKind.ZOMBIE_VILLAGER_CURE,
                    HealingAxeTuning.ATTACK_EXHAUSTION_COST,
                    HealingAxeTuning.ZERO_DAMAGE);
            }
            return new StrikeOutcome(
                StrikeKind.UNDEAD_MOB_DAMAGE,
                HealingAxeTuning.ATTACK_EXHAUSTION_COST,
                HealingAxeTuning.UNDEAD_MOB_DAMAGE);
        }
        return new StrikeOutcome(
            StrikeKind.NON_UNDEAD_NOOP,
            HealingAxeTuning.ATTACK_EXHAUSTION_COST,
            HealingAxeTuning.ZERO_DAMAGE);
    }
}
