/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemFilm.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.common.lifecycle.item;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleNbtKeys;
import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings;

/** 复活只恢复身份和女仆设置，死亡库存保持独立的一份 */
public final class MaidFilmData {

    private static final String[] REMOVED = { LifecycleItemIds.ID_ID, LifecycleItemNbtKeys.KEY_UUID_MOST,
        LifecycleItemNbtKeys.KEY_UUID_LEAST, LifecycleItemNbtKeys.KEY_PERSISTENT_IDMSB,
        LifecycleItemNbtKeys.KEY_PERSISTENT_IDLSB, LifecycleItemNbtKeys.KEY_TLMNH_RECOVERY,
        LifecycleItemNbtKeys.KEY_MAID_INVENTORY, MaidBaubleNbtKeys.ITEMS, LifecycleItemNbtKeys.KEY_MAID_EXPERIENCE,
        LifecycleItemNbtKeys.KEY_TLMNH_OFFHAND, LifecycleItemNbtKeys.KEY_EQUIPMENT,
        LifecycleItemNbtKeys.KEY_DROP_CHANCES, LifecycleItemNbtKeys.KEY_HEALTH, LifecycleItemNbtKeys.KEY_HEAL_F,
        LifecycleItemNbtKeys.KEY_HURT_TIME, LifecycleItemNbtKeys.KEY_DEATH_TIME, LifecycleItemNbtKeys.KEY_ATTACK_TIME,
        LifecycleItemNbtKeys.KEY_HURT_BY_TIMESTAMP, LifecycleItemNbtKeys.KEY_POS, LifecycleItemNbtKeys.KEY_MOTION,
        LifecycleItemNbtKeys.KEY_ROTATION, LifecycleItemNbtKeys.KEY_FALL_DISTANCE, LifecycleItemNbtKeys.KEY_FIRE,
        LifecycleItemNbtKeys.KEY_AIR, LifecycleItemNbtKeys.KEY_RIDING, LifecycleItemNbtKeys.KEY_LEASH,
        LifecycleItemNbtKeys.KEY_LEASHED, LifecycleItemNbtKeys.KEY_ACTIVE_EFFECTS,
        LifecycleItemNbtKeys.KEY_ABSORPTION_AMOUNT, LifecycleItemNbtKeys.KEY_INVULNERABLE };

    private MaidFilmData() {}

    public static NBTTagCompound identity(NBTTagCompound original) {
        var data = (NBTTagCompound) original.copy();
        for (var key : REMOVED) data.removeTag(key);
        data.setBoolean(LifecycleItemNbtKeys.KEY_SITTING, false);
        // 原maidToFilm先关闭home，再保存身份；保留各活动点而不让复活后仍被死亡地点束缚
        if (data.hasKey(LifecycleItemNbtKeys.KEY_TLMNH_HOME, Constants.NBT.TAG_COMPOUND)) {
            var home = data.getCompoundTag(LifecycleItemNbtKeys.KEY_TLMNH_HOME);
            try {
                if (home.getInteger(LifecycleItemNbtKeys.KEY_SCHEMA)
                    == LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT) {
                    var old = MaidHomeSettings.decode(home.getString(LifecycleItemNbtKeys.KEY_SETTINGS));
                    home.setString(
                        LifecycleItemNbtKeys.KEY_SETTINGS,
                        new MaidHomeSettings(false, old.configured, old.dimension, old.work, old.idle, old.rest)
                            .encode());
                }
            } catch (IllegalArgumentException invalid) {
                // MaidHomeArea的原读取边界会关闭无法识别的home；这里不重写或丢弃原始扩展
                ModContext.LOG.warn(LifecycleItemDiagnostics.MSG_UNREADABLE_HOME_SETTINGS_RETAINED_IN, invalid);
            }
        }
        return data;
    }
}
