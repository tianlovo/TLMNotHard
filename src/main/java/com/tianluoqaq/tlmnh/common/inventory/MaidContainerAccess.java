package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 女仆窗口共用的会话、归属、距离与世界实体身份检查 */
public final class MaidContainerAccess {

    private MaidContainerAccess() {}

    public static boolean canInteract(EntityPlayer viewer, EntityPlayer player, EntityMaid maid) {
        return player != null && player == viewer
            && maid != null
            && maid.getMaidInventory()
                .isUseableByPlayer(player)
            && player.worldObj.getEntityByID(maid.getEntityId()) == maid;
    }
}
