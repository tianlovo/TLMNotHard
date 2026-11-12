package com.tianluoqaq.tlmnh.compat.hungeroverhaul.common.hunger;

import java.lang.reflect.Field;

import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.life.hunger.spi.HungerPolicyCompatProvider;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.hungeroverhaul.HungerOverhaulCompat;
import com.tianluoqaq.tlmnh.compat.hungeroverhaul.HungerOverhaulIds;

import cpw.mods.fml.relauncher.Side;

/** HungerOverhaul 参数惰性读取实现，未安装时自动回退原版饥饿策略 */
public final class HungerOverhaulPolicyCompat implements HungerPolicyCompatProvider {

    public static final HungerOverhaulPolicyCompat INSTANCE = new HungerOverhaulPolicyCompat();

    private static volatile Api cachedApi;
    private static volatile boolean resolved;

    private HungerOverhaulPolicyCompat() {}

    @Override
    public boolean isHungerOverhaulActive() {
        return OptionalApi.isModLoaded(HungerOverhaulCompat.MOD_ID)
            && IntegrationGuard.available(Side.SERVER, HungerOverhaulIds.GUARD)
            && api() != null;
    }

    @Override
    public int minHungerToHeal(int vanillaMinHunger) {
        if (!isHungerOverhaulActive()) return vanillaMinHunger;
        try {
            return Math.max(HungerOverhaulTuning.ZERO_INT, api().minHungerToHeal.getInt(null));
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, HungerOverhaulIds.GUARD, failure);
            return vanillaMinHunger;
        }
    }

    @Override
    public int computeRegenPeriodTicks(World world, float health, float maxHealth, int vanillaPeriodTicks) {
        if (!isHungerOverhaulActive()) return vanillaPeriodTicks;
        try {
            Api a = api();
            int ratePct = a.healthRegenRatePct.getInt(null);
            if (ratePct <= 0) return HungerOverhaulTuning.MAX_REGEN_PERIOD_TICKS;
            float period = vanillaPeriodTicks
                * (HungerOverhaulTuning.PERCENT_BASE / Math.max(HungerOverhaulTuning.MIN_RATE_PCT, (float) ratePct));
            float diffFactor = HungerOverhaulTuning.DIFF_FACTOR_NORMAL;
            if (a.difficultyScaling.getBoolean(null) && a.difficultyScalingHealing.getBoolean(null)
                && world != null
                && world.difficultySetting != null) {
                int diff = world.difficultySetting.getDifficultyId();
                if (diff <= HungerOverhaulTuning.EASY_DIFFICULTY) {
                    diffFactor = HungerOverhaulTuning.DIFF_FACTOR_EASY;
                } else if (diff >= HungerOverhaulTuning.HARD_DIFFICULTY) {
                    diffFactor = HungerOverhaulTuning.DIFF_FACTOR_HARD;
                }
            }
            if (a.modifyRegenLowHealth.getBoolean(null) && maxHealth > health) {
                int lowMod = Math.max(HungerOverhaulTuning.ZERO_INT, a.lowHealthRegenMod.getInt(null));
                float missing = maxHealth - health;
                double base = HungerOverhaulTuning.UNIT_FACTOR
                    + missing * (lowMod * HungerOverhaulTuning.LOW_HEALTH_STEP) * diffFactor;
                period *= (float) Math.pow(base, HungerOverhaulTuning.LOW_HEALTH_EXPONENT);
            } else {
                period *= diffFactor;
            }
            int rounded = Math.round(period);
            return Math.max(
                HungerOverhaulTuning.MIN_REGEN_PERIOD_TICKS,
                Math.min(HungerOverhaulTuning.MAX_REGEN_PERIOD_TICKS, rounded));
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, HungerOverhaulIds.GUARD, failure);
            return vanillaPeriodTicks;
        }
    }

    @Override
    public boolean drainsExhaustionOnHeal() {
        if (!isHungerOverhaulActive()) return true;
        try {
            return !api().disableHealingDrain.getBoolean(null);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, HungerOverhaulIds.GUARD, failure);
            return true;
        }
    }

    @Override
    public float computeStarveDamage(World world, float currentHealth) {
        if (!isHungerOverhaulActive()) {
            return HungerPolicyCompatProvider.NOOP.computeStarveDamage(world, currentHealth);
        }
        if (world != null && world.difficultySetting != null
            && world.difficultySetting.getDifficultyId() <= HungerOverhaulTuning.PEACEFUL_DIFFICULTY) {
            return HungerOverhaulTuning.ZERO_DAMAGE;
        }
        try {
            int dmg = api().damageOnStarve.getInt(null);
            return dmg > 0 ? (float) dmg : HungerPolicyCompatProvider.NOOP.computeStarveDamage(world, currentHealth);
        } catch (Exception | LinkageError failure) {
            IntegrationGuard.report(Side.SERVER, HungerOverhaulIds.GUARD, failure);
            return HungerPolicyCompatProvider.NOOP.computeStarveDamage(world, currentHealth);
        }
    }

    private static Api api() {
        if (resolved) return cachedApi;
        synchronized (HungerOverhaulPolicyCompat.class) {
            if (resolved) return cachedApi;
            try {
                Class<?> cls = Class.forName(
                    HungerOverhaulReflectionSpec.CONFIG_CLASS,
                    false,
                    HungerOverhaulPolicyCompat.class.getClassLoader());
                cachedApi = new Api(
                    cls.getField(HungerOverhaulReflectionSpec.FLD_MIN_HUNGER_TO_HEAL),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_HEALTH_REGEN_RATE_PCT),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_MODIFY_REGEN_LOW_HEALTH),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_LOW_HEALTH_REGEN_MOD),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_DIFFICULTY_SCALING),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_DIFFICULTY_SCALING_HEALING),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_DISABLE_HEALING_DRAIN),
                    cls.getField(HungerOverhaulReflectionSpec.FLD_DAMAGE_ON_STARVE));
            } catch (Exception | LinkageError failure) {
                IntegrationGuard.report(Side.SERVER, HungerOverhaulIds.GUARD, failure);
                cachedApi = null;
            }
            resolved = true;
            return cachedApi;
        }
    }

    private static final class Api {

        final Field minHungerToHeal;
        final Field healthRegenRatePct;
        final Field modifyRegenLowHealth;
        final Field lowHealthRegenMod;
        final Field difficultyScaling;
        final Field difficultyScalingHealing;
        final Field disableHealingDrain;
        final Field damageOnStarve;

        Api(Field minHungerToHeal, Field healthRegenRatePct, Field modifyRegenLowHealth, Field lowHealthRegenMod,
            Field difficultyScaling, Field difficultyScalingHealing, Field disableHealingDrain, Field damageOnStarve) {
            this.minHungerToHeal = minHungerToHeal;
            this.healthRegenRatePct = healthRegenRatePct;
            this.modifyRegenLowHealth = modifyRegenLowHealth;
            this.lowHealthRegenMod = lowHealthRegenMod;
            this.difficultyScaling = difficultyScaling;
            this.difficultyScalingHealing = difficultyScalingHealing;
            this.disableHealingDrain = disableHealingDrain;
            this.damageOnStarve = damageOnStarve;
        }
    }
}
