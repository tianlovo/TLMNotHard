package com.tianluoqaq.tlmnh.common.combat.spi;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** ExtraUtilities 治愈之斧（defoliageAxe）副手回饥饿与主手特殊近战结算窄接口 */
public interface HealingAxeCompatProvider {

    HealingAxeCompatProvider NOOP = new HealingAxeCompatProvider() {};

    enum StrikeKind {
        NOT_APPLICABLE,
        ZOMBIE_VILLAGER_CURE,
        UNDEAD_MOB_DAMAGE,
        NON_UNDEAD_NOOP
    }

    final class StrikeOutcome {

        public static final StrikeOutcome NOT_APPLICABLE_OUTCOME = new StrikeOutcome(
            StrikeKind.NOT_APPLICABLE,
            HealingAxeSpiTuning.ZERO_COST,
            HealingAxeSpiTuning.ZERO_DAMAGE);

        public final StrikeKind kind;
        public final float exhaustionCost;
        public final float undeadDamage;

        public StrikeOutcome(StrikeKind kind, float exhaustionCost, float undeadDamage) {
            this.kind = kind;
            this.exhaustionCost = exhaustionCost;
            this.undeadDamage = undeadDamage;
        }
    }

    interface HungerRestoreSink {

        void addStats(int food, float saturationModifier);
    }

    default boolean isHealingAxe(ItemStack stack) {
        return false;
    }

    default boolean tickOffhandPassiveHunger(World world, ItemStack offhandStack, HungerRestoreSink sink) {
        return false;
    }

    default StrikeOutcome resolveMainhandStrike(ItemStack mainhandStack, Entity target) {
        return StrikeOutcome.NOT_APPLICABLE_OUTCOME;
    }
}
