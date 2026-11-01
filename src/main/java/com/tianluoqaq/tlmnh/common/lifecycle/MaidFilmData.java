/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemFilm.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.nbt.NBTTagCompound;

/** 复活只恢复身份和女仆设置，死亡库存保持独立的一份 */
public final class MaidFilmData {

    private static final String[] REMOVED = { "id", "UUIDMost", "UUIDLeast", "PersistentIDMSB", "PersistentIDLSB",
        "TLMNHRecovery", "MaidInventory", "MaidExperience", "TLMNHOffhand", "Equipment", "DropChances", "Health",
        "HealF", "HurtTime", "DeathTime", "AttackTime", "HurtByTimestamp", "Pos", "Motion", "Rotation", "FallDistance",
        "Fire", "Air", "Riding", "Leash", "Leashed", "ActiveEffects", "AbsorptionAmount", "Invulnerable" };

    private MaidFilmData() {}

    public static NBTTagCompound identity(NBTTagCompound original) {
        var data = (NBTTagCompound) original.copy();
        for (var key : REMOVED) data.removeTag(key);
        data.setBoolean("Sitting", false);
        return data;
    }
}
