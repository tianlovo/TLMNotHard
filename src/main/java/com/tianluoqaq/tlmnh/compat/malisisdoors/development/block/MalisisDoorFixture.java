package com.tianluoqaq.tlmnh.compat.malisisdoors.development.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/** 同步开发夹具使用的已核动画存档合同，不用于生产开门 */
public final class MalisisDoorFixture {

    private MalisisDoorFixture() {}

    public static boolean isAnimated(TileEntity tile) {
        return tile != null && tile.getClass()
            .getName()
            .startsWith("net.malisis.doors.");
    }

    public static void completeOpening(TileEntity tile) {
        var completed = new NBTTagCompound();
        tile.writeToNBT(completed);
        completed.setInteger("state", 2);
        tile.readFromNBT(completed);
    }
}
