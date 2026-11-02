package com.tianluoqaq.tlmnh.common.life.bed;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;

/** 床头与物品共用原始颜色字段；未知编号/类型跨容器复制时也不能归一化 */
public final class MaidBedColorData {

    public static final String TAG = "BedColor";

    private MaidBedColorData() {}

    public static NBTBase read(NBTTagCompound tag) {
        return tag != null && tag.hasKey(TAG) ? tag.getTag(TAG)
            .copy() : null;
    }

    public static MaidBedColor decode(NBTBase raw) {
        if (raw == null) return MaidBedColor.PINK;
        return raw instanceof NBTTagInt value ? MaidBedColor.byId(value.func_150287_d()) : null;
    }

    public static void write(NBTTagCompound tag, NBTBase raw) {
        tag.setTag(TAG, raw == null ? new NBTTagInt(MaidBedColor.PINK.id) : raw.copy());
    }
}
