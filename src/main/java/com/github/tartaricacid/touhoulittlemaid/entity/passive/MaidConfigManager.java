/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/passive/MaidConfigManager.java, MIT
 * Adaptation: versioned legacy metadata and original subconfig NBT keys
 */
package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import net.minecraft.entity.DataWatcher;
import net.minecraft.nbt.NBTTagCompound;

public final class MaidConfigManager {

    private static final int DATA_CONFIG = 31;
    private static final String MAID_SUB_CONFIG_TAG = "MaidSubConfig";
    private static final String SOUND_FREQ_TAG = "SoundFreq";
    private static final String PICKUP_TYPE_TAG = "PickupType";
    private static final String[] NAVIGATION_TAGS = { "OpenDoor", "OpenFenceGate", "ActiveClimbing" };
    private static final String[] VISUAL_TAGS = { "BackpackShow", "BackItemShow", "ChatBubbleShow" };
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
        sub.setInteger(PICKUP_TYPE_TAG, getPickupType().ordinal());
        for (int i = 0; i < NAVIGATION_TAGS.length; i++) sub.setBoolean(NAVIGATION_TAGS[i], flag(1 << i));
        for (int i = 0; i < VISUAL_TAGS.length; i++) sub.setBoolean(VISUAL_TAGS[i], (visualFlags() & (1 << i)) != 0);
        compound.setTag(MAID_SUB_CONFIG_TAG, sub);
    }

    void readAdditionalSaveData(NBTTagCompound compound) {
        unreadConfig = (NBTTagCompound) compound.getCompoundTag(MAID_SUB_CONFIG_TAG)
            .copy();
        if (unreadConfig.hasKey(SOUND_FREQ_TAG, 99)) {
            float frequency = unreadConfig.getFloat(SOUND_FREQ_TAG);
            if (Float.isFinite(frequency)) setSoundFreq(frequency);
        }
        if (unreadConfig.hasKey(PICKUP_TYPE_TAG, 99)) {
            int type = unreadConfig.getInteger(PICKUP_TYPE_TAG);
            setPickupType(type >= 0 && type < PickType.values().length ? PickType.values()[type] : PickType.ALL);
        }
        for (int i = 0; i < NAVIGATION_TAGS.length; i++) if (unreadConfig.hasKey(NAVIGATION_TAGS[i], 1))
            setFlag(1 << i, unreadConfig.getBoolean(NAVIGATION_TAGS[i]));
        for (int i = 0; i < VISUAL_TAGS.length; i++)
            if (unreadConfig.hasKey(VISUAL_TAGS[i], 1)) setVisualFlag(1 << i, unreadConfig.getBoolean(VISUAL_TAGS[i]));
    }

    public boolean isShowBackpack() {
        return (visualFlags() & 1) != 0;
    }

    public boolean isShowBackItem() {
        return (visualFlags() & 2) != 0;
    }

    public boolean isChatBubbleShow() {
        return (visualFlags() & 4) != 0;
    }

    public void setShowBackpack(boolean value) {
        setVisualFlag(1, value);
    }

    public void setShowBackItem(boolean value) {
        setVisualFlag(2, value);
    }

    public void setShowChatBubble(boolean value) {
        setVisualFlag(4, value);
    }

    private int visualFlags() {
        return field(4, 7) & 7;
    }

    private void setVisualFlag(int mask, boolean value) {
        write(getSoundFreq(), getPickupType(), flags(), value ? visualFlags() | mask : visualFlags() & ~mask);
    }

    public float getSoundFreq() {
        String raw = entityData.getWatchableObjectString(DATA_CONFIG);
        if (raw.length() > 80) return 1;
        try {
            String[] fields = raw.split(";", -1);
            if (!(fields.length == 2 && fields[0].equals("1") || fields.length == 4 && fields[0].equals("2")
                || fields.length == 5 && fields[0].equals("3"))) return 1;
            float frequency = Float.parseFloat(fields[1]);
            return Float.isFinite(frequency) ? Math.max(0, Math.min(1, frequency)) : 1;
        } catch (NumberFormatException invalid) {
            return 1;
        }
    }

    public void setSoundFreq(float frequency) {
        if (!Float.isFinite(frequency)) throw new IllegalArgumentException("Non-finite maid sound frequency");
        write(Math.max(0, Math.min(1, frequency)), getPickupType(), flags());
    }

    public PickType getPickupType() {
        int ordinal = field(2, PickType.ALL.ordinal());
        return ordinal >= 0 && ordinal < PickType.values().length ? PickType.values()[ordinal] : PickType.ALL;
    }

    public void setPickupType(PickType type) {
        write(getSoundFreq(), java.util.Objects.requireNonNull(type), flags());
    }

    public boolean isOpenDoor() {
        return flag(1);
    }

    public boolean isOpenFenceGate() {
        return flag(2);
    }

    public boolean isActiveClimbing() {
        return flag(4);
    }

    public void setOpenDoor(boolean value) {
        setFlag(1, value);
    }

    public void setOpenFenceGate(boolean value) {
        setFlag(2, value);
    }

    public void setActiveClimbing(boolean value) {
        setFlag(4, value);
    }

    private int flags() {
        return field(3, 7) & 7;
    }

    private boolean flag(int mask) {
        return (flags() & mask) != 0;
    }

    private void setFlag(int mask, boolean value) {
        write(getSoundFreq(), getPickupType(), value ? flags() | mask : flags() & ~mask);
    }

    private int field(int index, int fallback) {
        String raw = entityData.getWatchableObjectString(DATA_CONFIG);
        if (raw.length() > 80) return fallback;
        String[] fields = raw.split(";", -1);
        if (!(fields.length == 4 && fields[0].equals("2") || fields.length == 5 && fields[0].equals("3"))
            || index >= fields.length) return fallback;
        try {
            return Integer.parseInt(fields[index]);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private void write(float frequency, PickType type, int flags) {
        write(frequency, type, flags, visualFlags());
    }

    private void write(float frequency, PickType type, int flags, int visuals) {
        entityData.updateObject(DATA_CONFIG, "3;" + frequency + ";" + type.ordinal() + ";" + flags + ";" + visuals);
    }
}
