package com.tianluoqaq.tlmnh.common.life.hunger;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.life.hunger.spi.HungerPolicyCompatProvider;

/**
 * 女仆服务端饥饿状态机：
 * 维护食物值、饱和度、耗竭度与回血/饿伤计时；支持旧档无损迁移与未来/损坏标签安全暂停保留
 */
public final class MaidHungerState {

    private int food = MaidHungerTuning.MAX_FOOD;
    private float saturation = MaidHungerTuning.INITIAL_SATURATION;
    private float exhaustion;
    private int regenTicks;
    private int starveTicks;

    private boolean activated;
    private boolean paused;
    private boolean pausedWarned;
    private boolean hadLegacyBackupKey;
    private NBTBase preservedRawState;
    private NBTBase legacyMirror;
    private NBTBase legacyHungerBackup;

    public boolean isActivated() {
        return activated;
    }

    public int getFood() {
        return food;
    }

    public float getSaturation() {
        return saturation;
    }

    public float getExhaustion() {
        return exhaustion;
    }

    public int getRegenTicks() {
        return regenTicks;
    }

    public int getStarveTicks() {
        return starveTicks;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isHungry() {
        return !paused && food < MaidHungerTuning.MAX_FOOD;
    }

    public void setFood(int newFood) {
        if (paused) return;
        activated = true;
        food = clampFood(newFood);
        if (saturation > food) {
            saturation = food;
        }
    }

    public void setSaturation(float newSaturation) {
        if (paused || !Float.isFinite(newSaturation)) return;
        activated = true;
        saturation = Math.max(MaidHungerTuning.ZERO_FLOAT, Math.min((float) food, newSaturation));
    }

    public void addStats(int foodGain, float saturationModifier) {
        if (paused || foodGain <= 0 || !Float.isFinite(saturationModifier) || saturationModifier < 0.0F) return;
        activated = true;
        food = (int) Math.min(MaidHungerTuning.MAX_FOOD, (long) food + foodGain);
        float satGain = foodGain * saturationModifier * MaidHungerTuning.SATURATION_ADD_MULTIPLIER;
        saturation = Math.min((float) food, saturation + satGain);
    }

    public void addExhaustion(float amount) {
        if (paused || !Float.isFinite(amount) || amount <= 0.0F) return;
        activated = true;
        exhaustion = Math.min(MaidHungerTuning.MAX_EXHAUSTION, exhaustion + amount);
    }

    /** 服务端每 tick 推进一次饥饿折算、自然回血与零饥饿扣血 */
    public void tick(EntityLivingBase maid, HungerPolicyCompatProvider policy) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote || paused) return;
        activated = true;
        HungerPolicyCompatProvider activePolicy = policy != null ? policy : HungerPolicyCompatProvider.NOOP;

        if (exhaustion >= MaidHungerTuning.EXHAUSTION_STEP) {
            exhaustion -= MaidHungerTuning.EXHAUSTION_STEP;
            if (saturation > 0.0F) {
                saturation = Math.max(MaidHungerTuning.ZERO_FLOAT, saturation - MaidHungerTuning.SATURATION_DRAIN_UNIT);
            } else {
                food = Math.max(MaidHungerTuning.MIN_FOOD, food - 1);
            }
        }

        World world = maid.worldObj;
        boolean naturalRegen = world.getGameRules() == null || world.getGameRules()
            .getGameRuleBooleanValue(MaidHungerDiagnostics.GAMERULE_NATURAL_REGEN);

        int healThreshold = activePolicy.minHungerToHeal(MaidHungerTuning.VANILLA_MIN_HUNGER_TO_HEAL);
        float health = maid.getHealth();
        float maxHealth = maid.getMaxHealth();
        if (naturalRegen && food >= healThreshold && health > 0.0F && health < maxHealth) {
            if (regenTicks < Integer.MAX_VALUE) regenTicks++;
            int period = activePolicy
                .computeRegenPeriodTicks(world, health, maxHealth, MaidHungerTuning.VANILLA_REGEN_PERIOD_TICKS);
            if (regenTicks >= Math.max(MaidHungerTuning.MIN_PERIOD_TICKS, period)) {
                maid.heal(MaidHungerTuning.NATURAL_HEAL_AMOUNT);
                if (activePolicy.drainsExhaustionOnHeal()) {
                    addExhaustion(MaidHungerTuning.VANILLA_HEAL_EXHAUSTION);
                }
                regenTicks = MaidHungerTuning.ZERO_TICKS;
            }
        } else {
            regenTicks = MaidHungerTuning.ZERO_TICKS;
        }

        if (food <= 0) {
            if (starveTicks < Integer.MAX_VALUE) starveTicks++;
            if (starveTicks >= MaidHungerTuning.STARVE_PERIOD_TICKS) {
                float dmg = activePolicy.computeStarveDamage(world, maid.getHealth());
                if (dmg > 0.0F) {
                    maid.attackEntityFrom(DamageSource.starve, dmg);
                }
                starveTicks = MaidHungerTuning.ZERO_TICKS;
            }
        } else {
            starveTicks = MaidHungerTuning.ZERO_TICKS;
        }
    }

    public void readFromNBT(NBTTagCompound root) {
        if (root == null) return;
        paused = false;
        preservedRawState = null;
        legacyMirror = root.hasKey(MaidHungerNbtKeys.LEGACY_HUNGER) ? root.getTag(MaidHungerNbtKeys.LEGACY_HUNGER)
            .copy() : null;

        if (root.hasKey(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP)) {
            hadLegacyBackupKey = true;
            legacyHungerBackup = root.getTag(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP)
                .copy();
        } else if (root.hasKey(MaidHungerNbtKeys.LEGACY_HUNGER)) {
            hadLegacyBackupKey = false;
            legacyHungerBackup = root.getTag(MaidHungerNbtKeys.LEGACY_HUNGER)
                .copy();
        } else {
            hadLegacyBackupKey = false;
            legacyHungerBackup = null;
        }

        if (!root.hasKey(MaidHungerNbtKeys.ROOT_TAG)) {
            resetFullDefaults();
            activated = false;
            return;
        }

        if (!root.hasKey(MaidHungerNbtKeys.ROOT_TAG, MaidHungerTuning.NBT_TAG_COMPOUND)) {
            enterSafePause(
                root.getTag(MaidHungerNbtKeys.ROOT_TAG)
                    .copy());
            return;
        }

        NBTTagCompound state = root.getCompoundTag(MaidHungerNbtKeys.ROOT_TAG);
        if (!isValidStateCompound(state)) {
            enterSafePause(state.copy());
            return;
        }

        int f = state.getInteger(MaidHungerNbtKeys.FOOD);
        float sat = state.getFloat(MaidHungerNbtKeys.SATURATION);
        float exh = state.getFloat(MaidHungerNbtKeys.EXHAUSTION);
        int reg = state.getInteger(MaidHungerNbtKeys.REGEN_TICKS);
        int stv = state.getInteger(MaidHungerNbtKeys.STARVE_TICKS);

        this.food = f;
        this.saturation = Math.min((float) f, sat);
        this.exhaustion = exh;
        this.regenTicks = reg;
        this.starveTicks = stv;
        this.activated = true;
    }

    public void writeToNBT(NBTTagCompound root) {
        if (root == null) return;
        if (paused) {
            if (legacyMirror != null) root.setTag(MaidHungerNbtKeys.LEGACY_HUNGER, legacyMirror.copy());
            if (preservedRawState != null) {
                root.setTag(MaidHungerNbtKeys.ROOT_TAG, preservedRawState.copy());
            }
            if (hadLegacyBackupKey) {
                writeLegacyBackupIfPresent(root);
            }
            return;
        }
        if (!activated && isDefaultFullState()) {
            if (hadLegacyBackupKey) writeLegacyBackupIfPresent(root);
            if (legacyMirror != null) root.setTag(MaidHungerNbtKeys.LEGACY_HUNGER, legacyMirror.copy());
            root.removeTag(MaidHungerNbtKeys.ROOT_TAG);
            return;
        }
        writeLegacyBackupIfPresent(root);
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger(MaidHungerNbtKeys.VERSION, MaidHungerTuning.SCHEMA_VERSION);
        state.setInteger(MaidHungerNbtKeys.FOOD, food);
        state.setFloat(MaidHungerNbtKeys.SATURATION, saturation);
        state.setFloat(MaidHungerNbtKeys.EXHAUSTION, exhaustion);
        state.setInteger(MaidHungerNbtKeys.REGEN_TICKS, regenTicks);
        state.setInteger(MaidHungerNbtKeys.STARVE_TICKS, starveTicks);
        root.setTag(MaidHungerNbtKeys.ROOT_TAG, state);
        root.setInteger(MaidHungerNbtKeys.LEGACY_HUNGER, food);
    }

    private void writeLegacyBackupIfPresent(NBTTagCompound root) {
        if (legacyHungerBackup != null && !root.hasKey(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP)) {
            root.setTag(MaidHungerNbtKeys.LEGACY_HUNGER_BACKUP, legacyHungerBackup.copy());
        }
    }

    private void enterSafePause(NBTBase raw) {
        paused = true;
        preservedRawState = raw;
        if (!pausedWarned) {
            pausedWarned = true;
            if (ModContext.LOG != null) {
                ModContext.LOG.warn(MaidHungerDiagnostics.WARN_PAUSED_INVALID_TAG);
            }
        }
    }

    private void resetFullDefaults() {
        food = MaidHungerTuning.MAX_FOOD;
        saturation = MaidHungerTuning.INITIAL_SATURATION;
        exhaustion = MaidHungerTuning.ZERO_FLOAT;
        regenTicks = MaidHungerTuning.ZERO_TICKS;
        starveTicks = MaidHungerTuning.ZERO_TICKS;
    }

    private boolean isDefaultFullState() {
        return food == MaidHungerTuning.MAX_FOOD && Float.compare(saturation, MaidHungerTuning.INITIAL_SATURATION) == 0
            && Float.compare(exhaustion, MaidHungerTuning.ZERO_FLOAT) == 0
            && regenTicks == 0
            && starveTicks == 0;
    }

    private static boolean isValidStateCompound(NBTTagCompound state) {
        if (!state.hasKey(MaidHungerNbtKeys.VERSION, MaidHungerTuning.NBT_TAG_INT)
            || !state.hasKey(MaidHungerNbtKeys.FOOD, MaidHungerTuning.NBT_TAG_INT)
            || !state.hasKey(MaidHungerNbtKeys.SATURATION, MaidHungerTuning.NBT_TAG_FLOAT)
            || !state.hasKey(MaidHungerNbtKeys.EXHAUSTION, MaidHungerTuning.NBT_TAG_FLOAT)
            || !state.hasKey(MaidHungerNbtKeys.REGEN_TICKS, MaidHungerTuning.NBT_TAG_INT)
            || !state.hasKey(MaidHungerNbtKeys.STARVE_TICKS, MaidHungerTuning.NBT_TAG_INT)) {
            return false;
        }
        int ver = state.getInteger(MaidHungerNbtKeys.VERSION);
        if (ver != MaidHungerTuning.SCHEMA_VERSION) return false;
        int f = state.getInteger(MaidHungerNbtKeys.FOOD);
        float sat = state.getFloat(MaidHungerNbtKeys.SATURATION);
        float exh = state.getFloat(MaidHungerNbtKeys.EXHAUSTION);
        int reg = state.getInteger(MaidHungerNbtKeys.REGEN_TICKS);
        int stv = state.getInteger(MaidHungerNbtKeys.STARVE_TICKS);
        return f >= 0 && f <= MaidHungerTuning.MAX_FOOD
            && Float.isFinite(sat)
            && sat >= 0.0F
            && sat <= f
            && Float.isFinite(exh)
            && exh >= 0.0F
            && exh <= MaidHungerTuning.MAX_EXHAUSTION
            && reg >= 0
            && stv >= 0;
    }

    private static int clampFood(int v) {
        return Math.max(MaidHungerTuning.MIN_FOOD, Math.min(MaidHungerTuning.MAX_FOOD, v));
    }
}
