package com.tianluoqaq.tlmnh.common.life.space.spi;

import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Galacticraft 与 GalaxySpace 维度大气检测、饰品供氧消耗、缺氧事件与 1/2 阶隔热内衬窄接口 */
public interface SpaceSurvivalCompatProvider {

    SpaceSurvivalCompatProvider NOOP = new SpaceSurvivalCompatProvider() {};

    final class AtmosphereSample {

        public static final AtmosphereSample BREATHABLE_OVERWORLD = new AtmosphereSample(
            false,
            true,
            false,
            SpaceSpiTuning.NEUTRAL_THERMAL_MODIFIER);

        public final boolean galacticraftDimension;
        public final boolean breathableAtmosphere;
        public final boolean noAtmosphericCombustion;
        public final float thermalLevelModifier;

        public AtmosphereSample(boolean galacticraftDimension, boolean breathableAtmosphere,
            boolean noAtmosphericCombustion, float thermalLevelModifier) {
            this.galacticraftDimension = galacticraftDimension;
            this.breathableAtmosphere = breathableAtmosphere;
            this.noAtmosphericCombustion = noAtmosphericCombustion;
            this.thermalLevelModifier = thermalLevelModifier;
        }
    }

    final class OxygenCheckResult {

        public static final OxygenCheckResult INVALID = new OxygenCheckResult(
            false,
            false,
            SpaceSpiTuning.NO_DRAIN_SPACING);

        public final boolean validSetup;
        public final boolean infiniteSupply;
        public final int drainSpacing;

        public OxygenCheckResult(boolean validSetup, boolean infiniteSupply, int drainSpacing) {
            this.validSetup = validSetup;
            this.infiniteSupply = infiniteSupply;
            this.drainSpacing = drainSpacing;
        }
    }

    /** 仅由 FML 初始化回调安装原生事件监听，ServiceLoader 发现保持只装配实现 */
    default void registerNativeEvents() {}

    default boolean isAvailable() {
        return false;
    }

    default boolean isSpaceBaubleItem(ItemStack stack) {
        return false;
    }

    default AtmosphereSample sampleAtmosphere(World world) {
        return AtmosphereSample.BREATHABLE_OVERWORLD;
    }

    default boolean isInBreathableAir(EntityLivingBase maid, boolean checkThermal) {
        return true;
    }

    default OxygenCheckResult checkAndDrainOxygen(EntityLivingBase maid, List<ItemStack> baubleStacks,
        int ticksExisted) {
        return OxygenCheckResult.INVALID;
    }

    default float computeThermalStrength(List<ItemStack> baubleStacks) {
        return SpaceSpiTuning.NO_THERMAL_STRENGTH;
    }

    default int thermalPieceCount(List<ItemStack> baubleStacks) {
        return SpaceSpiTuning.NO_THERMAL_PIECES;
    }

    default boolean hardModeSuffocation() {
        return false;
    }

    default boolean postSuffocationPre(EntityLivingBase maid) {
        return false;
    }

    default void postSuffocationPost(EntityLivingBase maid) {}

    default int suffocationCooldown() {
        return SpaceSpiTuning.DEFAULT_SUFFOCATION_COOLDOWN;
    }

    default int suffocationDamageBase() {
        return SpaceSpiTuning.DEFAULT_SUFFOCATION_DAMAGE;
    }

    default boolean applySuffocationDamage(EntityLivingBase maid, float damage) {
        return false;
    }

    default boolean applyThermalDamage(EntityLivingBase maid, float damage) {
        return false;
    }
}
