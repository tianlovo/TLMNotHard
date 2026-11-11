/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemFilm.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings;

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
        // 原maidToFilm先关闭home，再保存身份；保留各活动点而不让复活后仍被死亡地点束缚
        if (data.hasKey("TLMNHHome", Constants.NBT.TAG_COMPOUND)) {
            var home = data.getCompoundTag("TLMNHHome");
            try {
                if (home.getInteger("Schema") == 1) {
                    var old = MaidHomeSettings.decode(home.getString("Settings"));
                    home.setString(
                        "Settings",
                        new MaidHomeSettings(false, old.configured, old.dimension, old.work, old.idle, old.rest)
                            .encode());
                }
            } catch (IllegalArgumentException invalid) {
                // MaidHomeArea的原读取边界会关闭无法识别的home；这里不重写或丢弃原始扩展
                TLMNotHard.LOG
                    .warn("Unreadable home settings retained in maid film; restored home remains disabled", invalid);
            }
        }
        return data;
    }
}
