package com.tianluoqaq.tlmnh.common.life.hunger.spi;

import net.minecraft.world.World;

/** 女仆自然回血门限、回血周期与饥饿扣血策略扩展窄接口（支持 HungerOverhaul 可选接入与原版回退） */
public interface HungerPolicyCompatProvider {

    HungerPolicyCompatProvider NOOP = new HungerPolicyCompatProvider() {};

    default boolean isHungerOverhaulActive() {
        return false;
    }

    default int minHungerToHeal(int vanillaMinHunger) {
        return vanillaMinHunger;
    }

    default int computeRegenPeriodTicks(World world, float health, float maxHealth, int vanillaPeriodTicks) {
        return vanillaPeriodTicks;
    }

    default boolean drainsExhaustionOnHeal() {
        return true;
    }

    default float computeStarveDamage(World world, float currentHealth) {
        if (world == null || world.difficultySetting == null) return HungerSpiTuning.ZERO_STARVE_DAMAGE;
        int diff = world.difficultySetting.getDifficultyId();
        if (diff <= HungerSpiTuning.PEACEFUL_DIFFICULTY) return HungerSpiTuning.ZERO_STARVE_DAMAGE;
        if (diff == HungerSpiTuning.EASY_DIFFICULTY && currentHealth <= HungerSpiTuning.EASY_HEALTH_FLOOR) {
            return HungerSpiTuning.ZERO_STARVE_DAMAGE;
        }
        if (diff == HungerSpiTuning.NORMAL_DIFFICULTY && currentHealth <= HungerSpiTuning.NORMAL_HEALTH_FLOOR) {
            return HungerSpiTuning.ZERO_STARVE_DAMAGE;
        }
        return HungerSpiTuning.VANILLA_STARVE_DAMAGE;
    }
}
