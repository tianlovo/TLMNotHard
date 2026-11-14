package com.tianluoqaq.tlmnh.common.combat.ballistics;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/** 通过原版公开存档方法读取箭的嵌入状态，不把遗留速度当成仍在飞行 */
public final class NativeArrowState {

    private NativeArrowState() {}

    public static boolean embedded(EntityArrow arrow) {
        if (arrow == null || arrow.getClass() != EntityArrow.class) return false;
        var state = new NBTTagCompound();
        arrow.writeEntityToNBT(state);
        return state.hasKey(BallisticsIds.NBT_IN_GROUND, Constants.NBT.TAG_BYTE)
            && state.getBoolean(BallisticsIds.NBT_IN_GROUND);
    }
}
