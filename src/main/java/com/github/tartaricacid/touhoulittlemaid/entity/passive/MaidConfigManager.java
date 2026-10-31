/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/MaidConfigManager.java, MIT
 * Adaptation: sound frequency, original NBT keys and one extensible legacy metadata slot
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import net.minecraft.entity.DataWatcher;
import net.minecraft.nbt.NBTTagCompound;

public final class MaidConfigManager {

    private static final int DATA_CONFIG = 31;
    private static final String MAID_SUB_CONFIG_TAG = "MaidSubConfig";
    private static final String SOUND_FREQ_TAG = "SoundFreq";
    private final DataWatcher entityData;
    private NBTTagCompound unreadConfig = new NBTTagCompound();

    MaidConfigManager(DataWatcher entityData) {
        this.entityData = entityData;
    }

    static void defineSynchedData(DataWatcher watcher) {
        // Float 类型的 31 号元数据会与旧协议的 0x7f 终止字节重合，使用带版本的字符串
        watcher.addObject(DATA_CONFIG, "1;1.0");
    }

    void addAdditionalSaveData(NBTTagCompound compound) {
        var sub = (NBTTagCompound) unreadConfig.copy();
        sub.setFloat(SOUND_FREQ_TAG, getSoundFreq());
        compound.setTag(MAID_SUB_CONFIG_TAG, sub);
    }

    void readAdditionalSaveData(NBTTagCompound compound) {
        unreadConfig = (NBTTagCompound) compound.getCompoundTag(MAID_SUB_CONFIG_TAG)
            .copy();
        if (unreadConfig.hasKey(SOUND_FREQ_TAG, 99)) {
            float frequency = unreadConfig.getFloat(SOUND_FREQ_TAG);
            if (Float.isFinite(frequency)) setSoundFreq(frequency);
        }
    }

    public float getSoundFreq() {
        String raw = entityData.getWatchableObjectString(DATA_CONFIG);
        if (!raw.startsWith("1;") || raw.length() > 32) return 1;
        try {
            float frequency = Float.parseFloat(raw.substring(2));
            return Float.isFinite(frequency) ? Math.max(0, Math.min(1, frequency)) : 1;
        } catch (NumberFormatException invalid) {
            return 1;
        }
    }

    public void setSoundFreq(float frequency) {
        if (!Float.isFinite(frequency)) throw new IllegalArgumentException("Non-finite maid sound frequency");
        entityData.updateObject(DATA_CONFIG, "1;" + Math.max(0, Math.min(1, frequency)));
    }
}
