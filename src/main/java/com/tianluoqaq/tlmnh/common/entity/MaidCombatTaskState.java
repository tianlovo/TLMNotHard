package com.tianluoqaq.tlmnh.common.entity;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/** 原始任务数据与生产任务投影分开，开发参照仍可表示旧弓兵身份 */
public final class MaidCombatTaskState {

    private NBTBase unresolvedTask;
    private NBTBase futureState;
    private NBTTagCompound preserved = new NBTTagCompound();
    private String originalId = MaidTask.IDLE.getId(), referenceId = MaidTask.IDLE.getId();
    private MaidTask preference = MaidTask.ATTACK;
    private boolean preferencePending;
    private boolean persistedState;
    private boolean loaded, explicitlySelected;
    private NBTBase loadedTask, loadedState;
    private long revision;

    public MaidTask task(String watchedId, boolean unified) {
        if (unified && futureState != null) return MaidTask.IDLE;
        MaidTask raw = MaidTask.fromId(watchedId);
        if (unified) return raw == MaidTask.RANGED_ATTACK ? MaidTask.ATTACK : raw;
        // 生产存档已经规范化时，开发参照从明确保留的旧身份恢复
        return raw == MaidTask.ATTACK && referenceId.equals(MaidTask.RANGED_ATTACK.getId()) ? MaidTask.RANGED_ATTACK
            : raw;
    }

    public void selected(MaidTask requested) {
        if (!canSelect()) return;
        explicitlySelected = true;
        unresolvedTask = null;
        referenceId = requested.getId();
        if (requested != MaidTask.IDLE) {
            preference = requested;
            preferencePending = true;
        }
        revision++;
    }

    public long revision() {
        return revision;
    }

    public boolean available() {
        return futureState == null && unresolvedTask == null;
    }

    public boolean canSelect() {
        return futureState == null;
    }

    public MaidTask preferredTask() {
        return preference;
    }

    public boolean preferencePending() {
        return preferencePending;
    }

    public void consumePreference() {
        preferencePending = false;
    }

    /** 返回可同步的已知身份，未解析原始标签仍由本对象持有 */
    public String load(NBTTagCompound entityTag) {
        loaded = true;
        explicitlySelected = false;
        unresolvedTask = null;
        futureState = null;
        var raw = entityTag.getTag(MaidCombatTaskSchema.TASK);
        loadedTask = raw == null ? null : raw.copy();
        String id = entityTag.hasKey(MaidCombatTaskSchema.TASK, Constants.NBT.TAG_STRING)
            ? entityTag.getString(MaidCombatTaskSchema.TASK)
            : MaidTask.IDLE.getId();
        boolean known = known(id)
            && (raw == null || entityTag.hasKey(MaidCombatTaskSchema.TASK, Constants.NBT.TAG_STRING));
        if (!known && raw != null) unresolvedTask = raw.copy();
        var extension = entityTag.getCompoundTag(MaidCombatTaskSchema.EXTENSION);
        var state = extension.getTag(MaidCombatTaskSchema.STATE);
        loadedState = state == null ? null : state.copy();
        persistedState = state != null;
        preserved = state instanceof NBTTagCompound compound ? (NBTTagCompound) compound.copy() : new NBTTagCompound();
        if (state != null && (!(state instanceof NBTTagCompound)
            || !preserved.hasKey(MaidCombatTaskSchema.VERSION, Constants.NBT.TAG_INT)
            || preserved.getInteger(MaidCombatTaskSchema.VERSION) != MaidCombatTaskSchema.CURRENT_VERSION))
            futureState = state.copy();
        if (futureState != null && raw != null) unresolvedTask = raw.copy();
        originalId = preserved.hasKey(MaidCombatTaskSchema.ORIGINAL, Constants.NBT.TAG_STRING)
            ? preserved.getString(MaidCombatTaskSchema.ORIGINAL)
            : id;
        referenceId = preserved.hasKey(MaidCombatTaskSchema.REFERENCE, Constants.NBT.TAG_STRING)
            ? preserved.getString(MaidCombatTaskSchema.REFERENCE)
            : id;
        String preferred = preserved.getString(MaidCombatTaskSchema.PREFERENCE);
        preference = known(preferred) && !preferred.equals(MaidTask.IDLE.getId()) ? MaidTask.fromId(preferred)
            : referenceId.equals(MaidTask.RANGED_ATTACK.getId()) ? MaidTask.RANGED_ATTACK : MaidTask.ATTACK;
        preferencePending = state == null ? known && !id.equals(MaidTask.IDLE.getId())
            : preserved.getBoolean(MaidCombatTaskSchema.PENDING);
        revision++;
        return known ? id : MaidTask.IDLE.getId();
    }

    public void save(NBTTagCompound entityTag, String watchedId, boolean unified) {
        if (!unified && loaded && !explicitlySelected) {
            if (loadedTask == null) entityTag.removeTag(MaidCombatTaskSchema.TASK);
            else entityTag.setTag(MaidCombatTaskSchema.TASK, loadedTask.copy());
            var extension = entityTag.getCompoundTag(MaidCombatTaskSchema.EXTENSION);
            if (loadedState == null) extension.removeTag(MaidCombatTaskSchema.STATE);
            else extension.setTag(MaidCombatTaskSchema.STATE, loadedState.copy());
            if (entityTag.hasKey(MaidCombatTaskSchema.EXTENSION) || loadedState != null)
                entityTag.setTag(MaidCombatTaskSchema.EXTENSION, extension);
            return;
        }
        if (unresolvedTask != null) entityTag.setTag(MaidCombatTaskSchema.TASK, unresolvedTask.copy());
        else entityTag.setString(MaidCombatTaskSchema.TASK, task(watchedId, unified).getId());
        if (!unified && !persistedState) return;
        persistedState = true;
        var extension = entityTag.getCompoundTag(MaidCombatTaskSchema.EXTENSION);
        if (futureState != null) extension.setTag(MaidCombatTaskSchema.STATE, futureState.copy());
        else {
            var state = (NBTTagCompound) preserved.copy();
            state.setInteger(MaidCombatTaskSchema.VERSION, MaidCombatTaskSchema.CURRENT_VERSION);
            state.setString(MaidCombatTaskSchema.ORIGINAL, originalId);
            state.setString(MaidCombatTaskSchema.REFERENCE, referenceId);
            state.setString(MaidCombatTaskSchema.PREFERENCE, preference.getId());
            state.setBoolean(MaidCombatTaskSchema.PENDING, preferencePending);
            extension.setTag(MaidCombatTaskSchema.STATE, state);
        }
        entityTag.setTag(MaidCombatTaskSchema.EXTENSION, extension);
    }

    private static boolean known(String id) {
        for (var task : MaidTask.values()) if (task.getId()
            .equals(id)) return true;
        return false;
    }
}
