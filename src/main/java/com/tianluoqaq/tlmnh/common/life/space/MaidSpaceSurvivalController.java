package com.tianluoqaq.tlmnh.common.life.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleInventory;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider.AtmosphereSample;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider.OxygenCheckResult;

/**
 * 女仆外星维度供氧消耗、缺氧窒息与 1/2 阶隔热内衬热力平衡控制器：
 * 主世界等可呼吸常规维度零开销快速返回；在外星维度严格按真实玩家链路执行氧气扣减与热力偏移结算
 */
public final class MaidSpaceSurvivalController {

    private int thermalLevel;
    private int thermalTimer;
    private int suffocationTicks;
    private int incrementalDamage;
    private boolean wasSuffocating;

    public int getThermalLevel() {
        return thermalLevel;
    }

    public int getSuffocationTicks() {
        return suffocationTicks;
    }

    public void tick(EntityMaid maid, SpaceSurvivalCompatProvider provider) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        SpaceSurvivalCompatProvider active = provider != null ? provider : SpaceSurvivalCompatProvider.NOOP;
        if (!active.isAvailable()) return;

        AtmosphereSample atmos = active.sampleAtmosphere(maid.worldObj);
        if (!atmos.galacticraftDimension) {
            resetIfReturnedToSafeDimension(maid, active);
            return;
        }

        List<ItemStack> baubles = collectUnlockedBaubles(maid);
        tickOxygenStatus(maid, baubles, atmos, active);
        tickThermalStatus(maid, baubles, atmos, active, maid.ticksExisted);
    }

    public void tickWithExplicitBaubles(EntityLivingBase entity, List<ItemStack> baubles, AtmosphereSample atmos,
        SpaceSurvivalCompatProvider provider, int ticksExisted) {
        if (entity == null || atmos == null || provider == null) return;
        if (!atmos.galacticraftDimension) {
            resetIfReturnedToSafeDimension(entity, provider);
            return;
        }
        tickOxygenInternal(entity, baubles, atmos, provider, ticksExisted);
        tickThermalStatus(entity, baubles, atmos, provider, ticksExisted);
    }

    private void tickOxygenStatus(EntityMaid maid, List<ItemStack> baubles, AtmosphereSample atmos,
        SpaceSurvivalCompatProvider active) {
        tickOxygenInternal(maid, baubles, atmos, active, maid.ticksExisted);
    }

    private void tickOxygenInternal(EntityLivingBase maid, List<ItemStack> baubles, AtmosphereSample atmos,
        SpaceSurvivalCompatProvider active, int ticksExisted) {
        boolean oxygenOk = atmos.breathableAtmosphere || active.isInBreathableAir(maid, false);
        if (!oxygenOk) {
            OxygenCheckResult check = active.checkAndDrainOxygen(maid, baubles, ticksExisted);
            oxygenOk = check.validSetup;
        }

        if (oxygenOk) {
            if (wasSuffocating) {
                wasSuffocating = false;
                suffocationTicks = SpaceSurvivalTuning.ZERO_INT;
                incrementalDamage = SpaceSurvivalTuning.ZERO_INT;

            }
            return;
        }

        wasSuffocating = true;
        if (suffocationTicks > 0) suffocationTicks--;
        int cooldown = Math.max(SpaceSurvivalTuning.MIN_POSITIVE_ONE, active.suffocationCooldown());
        if (!active.postSuffocationPre(maid) && suffocationTicks == 0) {
            suffocationTicks = cooldown;
            {
                int base = Math.max(SpaceSurvivalTuning.MIN_POSITIVE_ONE, active.suffocationDamageBase());
                float dmg = base * (SpaceSurvivalTuning.SUFFOCATION_BASE_FACTOR + incrementalDamage)
                    / SpaceSurvivalTuning.SUFFOCATION_DIVISOR;
                active.applySuffocationDamage(maid, dmg);
                active.postSuffocationPost(maid);
                if (active.hardModeSuffocation()) incrementalDamage++;
            }
        }
    }

    private void tickThermalStatus(EntityLivingBase maid, List<ItemStack> baubles, AtmosphereSample atmos,
        SpaceSurvivalCompatProvider active, int ticksExisted) {
        float mod = atmos.thermalLevelModifier;
        if (!Float.isFinite(mod) || Math.abs(mod) < SpaceSurvivalTuning.MIN_THERMAL_MODIFIER_ABS) {
            normaliseThermal(SpaceSurvivalTuning.ZERO_MOD_NORMALISE);
            return;
        }
        if (active.isInBreathableAir(maid, true)) {
            normaliseThermal(SpaceSurvivalTuning.MIN_POSITIVE_ONE);
            return;
        }
        int pieces = active.thermalPieceCount(baubles);
        float strength = active.computeThermalStrength(baubles);
        double absolute = Math.abs(mod);
        int base = Math.max(
            SpaceSurvivalTuning.MIN_POSITIVE_ONE,
            Math.abs((int) Math.floor(SpaceSurvivalTuning.THERMAL_INTERVAL_NUMERATOR / mod)));
        int hazard = base * (SpaceSurvivalTuning.MIN_POSITIVE_ONE + pieces);
        int normalisePeriod = Integer.MAX_VALUE;
        if (pieces == SpaceSurvivalTuning.THERMAL_PIECE_COUNT && strength > 0) {
            mod /= Math.max(SpaceSurvivalTuning.UNIT_FACTOR, strength / SpaceSurvivalTuning.THERMAL_STRENGTH_DIVISOR);
            absolute = Math.abs(mod);
            normalisePeriod = Math.max(
                SpaceSurvivalTuning.MIN_POSITIVE_ONE,
                (int) Math.floor(
                    Math.abs((int) Math.floor(SpaceSurvivalTuning.THERMAL_NORMALISE_NUMERATOR / strength)) / absolute));
        }
        int driftPeriod = Math.max(SpaceSurvivalTuning.MIN_POSITIVE_ONE, (int) Math.floor(hazard / absolute));
        int phase = Math.max(SpaceSurvivalTuning.ZERO_INT, ticksExisted - SpaceSurvivalTuning.MIN_POSITIVE_ONE);
        if (pieces == SpaceSurvivalTuning.THERMAL_PIECE_COUNT && phase % normalisePeriod == 0)
            normaliseThermal(SpaceSurvivalTuning.MIN_POSITIVE_ONE);
        if (phase % driftPeriod == 0) {
            thermalLevel = Math.max(
                -SpaceSurvivalTuning.MAX_THERMAL_LEVEL,
                Math.min(
                    SpaceSurvivalTuning.MAX_THERMAL_LEVEL,
                    thermalLevel
                        + (mod < 0 ? SpaceSurvivalTuning.STEP_MINUS_ONE : SpaceSurvivalTuning.MIN_POSITIVE_ONE)));
        }
        boolean normalising = pieces == SpaceSurvivalTuning.THERMAL_PIECE_COUNT && driftPeriod > normalisePeriod;
        if (normalising) return;
        if (phase % hazard == 0 && Math.abs(thermalLevel) >= SpaceSurvivalTuning.MAX_THERMAL_LEVEL)
            active.applyThermalDamage(maid, SpaceSurvivalTuning.THERMAL_DAMAGE_AMOUNT);
        if (thermalLevel < -SpaceSurvivalTuning.WARN_THERMAL_THRESHOLD) maid.addPotionEffect(
            new PotionEffect(
                Potion.moveSlowdown.id,
                SpaceSurvivalTuning.THERMAL_EFFECT_DURATION,
                SpaceSurvivalTuning.THERMAL_EFFECT_AMPLIFIER,
                true));
        if (thermalLevel > SpaceSurvivalTuning.WARN_THERMAL_THRESHOLD) maid.addPotionEffect(
            new PotionEffect(
                Potion.confusion.id,
                SpaceSurvivalTuning.THERMAL_EFFECT_DURATION,
                SpaceSurvivalTuning.THERMAL_EFFECT_AMPLIFIER,
                true));
    }

    private void normaliseThermal(int amount) {
        if (thermalLevel < 0) thermalLevel += Math.min(amount, -thermalLevel);
        else thermalLevel -= Math.min(amount, thermalLevel);
    }

    private void resetIfReturnedToSafeDimension(EntityLivingBase maid, SpaceSurvivalCompatProvider active) {
        wasSuffocating = false;
        suffocationTicks = SpaceSurvivalTuning.ZERO_INT;
        incrementalDamage = SpaceSurvivalTuning.ZERO_INT;
        normaliseThermal(SpaceSurvivalTuning.SAFE_DIM_NORMALISE);
    }

    private static List<ItemStack> collectUnlockedBaubles(EntityMaid maid) {
        MaidBaubleInventory inv = maid.getMaidBaubleInventory();
        int unlocked = MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel());
        int limit = Math.min(unlocked, inv.getSizeInventory());
        List<ItemStack> list = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > 0) list.add(s);
        }
        return list;
    }

    public void readFromNBT(NBTTagCompound root) {
        if (root == null || !root.hasKey(SpaceSurvivalNbtKeys.ROOT_TAG, SpaceSurvivalTuning.NBT_TAG_COMPOUND)) {
            thermalLevel = SpaceSurvivalTuning.ZERO_INT;
            thermalTimer = SpaceSurvivalTuning.ZERO_INT;
            suffocationTicks = SpaceSurvivalTuning.ZERO_INT;
            incrementalDamage = SpaceSurvivalTuning.ZERO_INT;
            wasSuffocating = false;
            return;
        }
        NBTTagCompound sub = root.getCompoundTag(SpaceSurvivalNbtKeys.ROOT_TAG);
        thermalLevel = Math.max(
            -SpaceSurvivalTuning.MAX_THERMAL_LEVEL,
            Math.min(SpaceSurvivalTuning.MAX_THERMAL_LEVEL, sub.getInteger(SpaceSurvivalNbtKeys.THERMAL_LEVEL)));
        thermalTimer = Math.max(SpaceSurvivalTuning.ZERO_INT, sub.getInteger(SpaceSurvivalNbtKeys.THERMAL_TIMER));
        suffocationTicks = Math.max(SpaceSurvivalTuning.ZERO_INT, sub.getInteger(SpaceSurvivalNbtKeys.OXYGEN_TICKS));
        incrementalDamage = Math
            .max(SpaceSurvivalTuning.ZERO_INT, sub.getInteger(SpaceSurvivalNbtKeys.OXYGEN_INCREMENT));
        wasSuffocating = suffocationTicks > 0 || incrementalDamage > 0;
    }

    public void writeToNBT(NBTTagCompound root) {
        if (root == null) return;
        if (thermalLevel == 0 && thermalTimer == 0 && suffocationTicks == 0 && incrementalDamage == 0) return;
        NBTTagCompound sub = new NBTTagCompound();
        sub.setInteger(SpaceSurvivalNbtKeys.THERMAL_LEVEL, thermalLevel);
        sub.setInteger(SpaceSurvivalNbtKeys.THERMAL_TIMER, thermalTimer);
        sub.setInteger(SpaceSurvivalNbtKeys.OXYGEN_TICKS, suffocationTicks);
        sub.setInteger(SpaceSurvivalNbtKeys.OXYGEN_INCREMENT, incrementalDamage);
        root.setTag(SpaceSurvivalNbtKeys.ROOT_TAG, sub);
    }
}
