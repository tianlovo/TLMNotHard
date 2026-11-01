package com.tianluoqaq.tlmnh.common.combat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.entity.data.inner.AttackListData;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;

/** 原TaskData中的攻击分类存档，保留本实现未解析的键与其他任务数据 */
public final class MaidAttackSettings {

    private static final String ROOT = "MaidTaskDataMaps", ID = "touhou_little_maid:maid_attack_list",
        GROUPS = "attack_groups";
    private final AttackListData data = AttackListData.empty();
    private final Set<String> parsedKeys = new HashSet<>();
    private NBTTagCompound preserved = new NBTTagCompound();

    public AttackListData data() {
        return data;
    }

    public void load(NBTTagCompound entityTag) {
        data.attackGroups()
            .clear();
        parsedKeys.clear();
        preserved = (NBTTagCompound) entityTag.getCompoundTag(ROOT)
            .copy();
        var groups = preserved.getCompoundTag(ID)
            .getCompoundTag(GROUPS);
        for (Object raw : groups.func_150296_c()) {
            String key = (String) raw;
            if (!groups.hasKey(key, 8) || !key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) continue;
            try {
                var type = MonsterType.valueOf(
                    groups.getString(key)
                        .toUpperCase(Locale.ENGLISH));
                data.attackGroups()
                    .put(new ResourceLocation(key), type);
                parsedKeys.add(key);
            } catch (IllegalArgumentException invalid) {
                // 未知新枚举/注册名保留在原NBT中，不让它参与当前服务端选敌
            }
        }
    }

    public void save(NBTTagCompound entityTag) {
        var root = (NBTTagCompound) preserved.copy();
        var entry = (NBTTagCompound) root.getCompoundTag(ID)
            .copy();
        var groups = (NBTTagCompound) entry.getCompoundTag(GROUPS)
            .copy();
        for (String key : parsedKeys) groups.removeTag(key);
        data.attackGroups()
            .forEach(
                (id, type) -> {
                    if (id != null && type != null) groups.setString(id.toString(), type.getSerializedName());
                });
        entry.setTag(GROUPS, groups);
        root.setTag(ID, entry);
        entityTag.setTag(ROOT, root);
    }
}
