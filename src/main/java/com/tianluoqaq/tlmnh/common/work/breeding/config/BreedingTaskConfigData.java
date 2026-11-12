package com.tianluoqaq.tlmnh.common.work.breeding.config;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 育种模式持久化配置：
 * 支持单品作物或变异池目标、Gain/Growth/Resistance 三维目标值、AND/OR 逻辑与满 31 属性模式。
 * 未配置的新旧存档女仆在写盘时不追加空根键，保证死亡赎回快照等既有实体标签保持原样
 */
public final class BreedingTaskConfigData {

    public enum TargetMode {
        CROP,
        MUTATION_POOL
    }

    public enum StatLogic {
        AND,
        OR
    }

    private TargetMode targetMode = TargetMode.CROP;
    private String targetId = "";
    private int targetGain = BreedingConfigTuning.MIN_STAT;
    private int targetGrowth = BreedingConfigTuning.MIN_STAT;
    private int targetResistance = BreedingConfigTuning.MIN_STAT;
    private StatLogic statLogic = StatLogic.AND;
    private boolean max31Mode;
    private boolean persistent;

    public BreedingTaskConfigData() {}

    public BreedingTaskConfigData copy() {
        BreedingTaskConfigData out = new BreedingTaskConfigData();
        out.copyFrom(this);
        return out;
    }

    public void copyFrom(BreedingTaskConfigData other) {
        if (other == null) {
            clear();
            return;
        }
        this.targetMode = other.targetMode == null ? TargetMode.CROP : other.targetMode;
        this.targetId = sanitizeId(other.targetId);
        this.targetGain = clampStat(other.targetGain);
        this.targetGrowth = clampStat(other.targetGrowth);
        this.targetResistance = clampStat(other.targetResistance);
        this.statLogic = other.statLogic == null ? StatLogic.AND : other.statLogic;
        this.max31Mode = other.max31Mode;
        this.persistent = other.persistent || !isDefault();
    }

    public void clear() {
        this.targetMode = TargetMode.CROP;
        this.targetId = "";
        this.targetGain = BreedingConfigTuning.MIN_STAT;
        this.targetGrowth = BreedingConfigTuning.MIN_STAT;
        this.targetResistance = BreedingConfigTuning.MIN_STAT;
        this.statLogic = StatLogic.AND;
        this.max31Mode = false;
        this.persistent = false;
    }

    public boolean isDefault() {
        return targetMode == TargetMode.CROP && targetId.isEmpty()
            && targetGain == BreedingConfigTuning.MIN_STAT
            && targetGrowth == BreedingConfigTuning.MIN_STAT
            && targetResistance == BreedingConfigTuning.MIN_STAT
            && statLogic == StatLogic.AND
            && !max31Mode;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void markPersistent() {
        this.persistent = true;
    }

    public boolean hasTarget() {
        return !targetId.isEmpty();
    }

    public TargetMode targetMode() {
        return targetMode;
    }

    public void setTargetMode(TargetMode mode) {
        this.targetMode = mode == null ? TargetMode.CROP : mode;
    }

    public boolean isMutationPoolMode() {
        return targetMode == TargetMode.MUTATION_POOL;
    }

    public String targetId() {
        return targetId;
    }

    public void setTargetId(String id) {
        this.targetId = sanitizeId(id);
    }

    public int targetGain() {
        return targetGain;
    }

    public void setTargetGain(int gain) {
        this.targetGain = clampStat(gain);
    }

    public int targetGrowth() {
        return targetGrowth;
    }

    public void setTargetGrowth(int growth) {
        this.targetGrowth = clampStat(growth);
    }

    public int targetResistance() {
        return targetResistance;
    }

    public void setTargetResistance(int resistance) {
        this.targetResistance = clampStat(resistance);
    }

    public int effectiveTargetGain() {
        return max31Mode ? BreedingConfigTuning.MAX_STAT : targetGain;
    }

    public int effectiveTargetGrowth() {
        return max31Mode ? BreedingConfigTuning.MAX_STAT : targetGrowth;
    }

    public int effectiveTargetResistance() {
        return max31Mode ? BreedingConfigTuning.MAX_STAT : targetResistance;
    }

    public StatLogic statLogic() {
        return statLogic;
    }

    public void setStatLogic(StatLogic logic) {
        this.statLogic = logic == null ? StatLogic.AND : logic;
    }

    public boolean max31Mode() {
        return max31Mode;
    }

    public void setMax31Mode(boolean enabled) {
        this.max31Mode = enabled;
    }

    public boolean meetsTargetStats(int growth, int gain, int resistance) {
        if (growth < BreedingConfigTuning.MIN_STAT || gain < BreedingConfigTuning.MIN_STAT
            || resistance < BreedingConfigTuning.MIN_STAT) {
            return false;
        }
        if (max31Mode) {
            return growth >= BreedingConfigTuning.MAX_STAT && gain >= BreedingConfigTuning.MAX_STAT
                && resistance >= BreedingConfigTuning.MAX_STAT;
        }
        boolean grOk = growth >= targetGrowth;
        boolean gaOk = gain >= targetGain;
        boolean reOk = resistance >= targetResistance;
        return statLogic == StatLogic.OR ? (grOk || gaOk || reOk) : (grOk && gaOk && reOk);
    }

    public int scoreSeed(int growth, int gain, int resistance) {
        int sum = growth + gain + resistance;
        int defGr = Math.max(effectiveTargetGrowth() - growth, BreedingConfigTuning.ZERO);
        int defGa = Math.max(effectiveTargetGain() - gain, BreedingConfigTuning.ZERO);
        int defRe = Math.max(effectiveTargetResistance() - resistance, BreedingConfigTuning.ZERO);
        return sum - (defGr + defGa + defRe) * BreedingConfigTuning.DEFICIT_PENALTY_WEIGHT;
    }

    public void writeToEntityNbt(NBTTagCompound entityTag) {
        if (entityTag == null) return;
        if (persistent || !isDefault()) {
            entityTag.setTag(BreedingConfigNbtKeys.ROOT, toCompound());
        } else {
            entityTag.removeTag(BreedingConfigNbtKeys.ROOT);
        }
    }

    public void readFromEntityNbt(NBTTagCompound entityTag) {
        if (entityTag == null || !entityTag.hasKey(BreedingConfigNbtKeys.ROOT, BreedingConfigTuning.NBT_COMPOUND_ID)) {
            clear();
            return;
        }
        fromCompound(entityTag.getCompoundTag(BreedingConfigNbtKeys.ROOT));
        this.persistent = true;
    }

    public NBTTagCompound toCompound() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(
            BreedingConfigNbtKeys.TARGET_MODE,
            targetMode == TargetMode.MUTATION_POOL ? BreedingConfigIds.MODE_MUTATION_POOL
                : BreedingConfigIds.MODE_CROP);
        tag.setString(BreedingConfigNbtKeys.TARGET_ID, targetId);
        tag.setByte(BreedingConfigNbtKeys.TARGET_GAIN, (byte) targetGain);
        tag.setByte(BreedingConfigNbtKeys.TARGET_GROWTH, (byte) targetGrowth);
        tag.setByte(BreedingConfigNbtKeys.TARGET_RESISTANCE, (byte) targetResistance);
        tag.setString(
            BreedingConfigNbtKeys.STAT_LOGIC,
            statLogic == StatLogic.OR ? BreedingConfigIds.LOGIC_OR : BreedingConfigIds.LOGIC_AND);
        tag.setBoolean(BreedingConfigNbtKeys.MAX_31_MODE, max31Mode);
        return tag;
    }

    public void fromCompound(NBTTagCompound tag) {
        if (tag == null) {
            clear();
            return;
        }
        String rawMode = tag.getString(BreedingConfigNbtKeys.TARGET_MODE);
        this.targetMode = BreedingConfigIds.MODE_MUTATION_POOL.equals(rawMode) ? TargetMode.MUTATION_POOL
            : TargetMode.CROP;
        this.targetId = sanitizeId(tag.getString(BreedingConfigNbtKeys.TARGET_ID));
        this.targetGain = clampStat(tag.getByte(BreedingConfigNbtKeys.TARGET_GAIN));
        this.targetGrowth = clampStat(tag.getByte(BreedingConfigNbtKeys.TARGET_GROWTH));
        this.targetResistance = clampStat(tag.getByte(BreedingConfigNbtKeys.TARGET_RESISTANCE));
        String rawLogic = tag.getString(BreedingConfigNbtKeys.STAT_LOGIC);
        this.statLogic = BreedingConfigIds.LOGIC_OR.equals(rawLogic) ? StatLogic.OR : StatLogic.AND;
        this.max31Mode = tag.getBoolean(BreedingConfigNbtKeys.MAX_31_MODE);
    }

    public static int clampStat(int value) {
        return Math.max(BreedingConfigTuning.MIN_STAT, Math.min(BreedingConfigTuning.MAX_STAT, value));
    }

    private static String sanitizeId(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.length() > BreedingConfigTuning.MAX_TARGET_ID_LENGTH
            ? trimmed.substring(BreedingConfigTuning.ZERO, BreedingConfigTuning.MAX_TARGET_ID_LENGTH)
            : trimmed;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BreedingTaskConfigData other)) return false;
        return targetMode == other.targetMode && Objects.equals(targetId, other.targetId)
            && targetGain == other.targetGain
            && targetGrowth == other.targetGrowth
            && targetResistance == other.targetResistance
            && statLogic == other.statLogic
            && max31Mode == other.max31Mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetMode, targetId, targetGain, targetGrowth, targetResistance, statLogic, max31Mode);
    }
}
