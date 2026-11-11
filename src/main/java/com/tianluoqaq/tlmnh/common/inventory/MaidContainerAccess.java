package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;

/** 女仆窗口共用的会话、归属、距离与世界实体身份检查 */
public final class MaidContainerAccess {

    private MaidContainerAccess() {}

    public static boolean canInteract(EntityPlayer viewer, EntityPlayer player, EntityMaid maid) {
        return player != null && player == viewer
            && maid != null
            && !MaidLifecycleService.isPendingRedemption(maid)
            && maid.getMaidInventory()
                .isUseableByPlayer(player)
            && player.worldObj.getEntityByID(maid.getEntityId()) == maid;
    }

    /** 写入沿用会话权限检查，并拒绝旁观模式 */
    public static boolean canModify(EntityPlayer viewer, EntityPlayer player, EntityMaid maid) {
        return canInteract(viewer, player, maid)
            && (!(player instanceof EntityPlayerMP serverPlayer) || serverPlayer.theItemInWorldManager.getGameType()
                .getID() != MaidInventoryLayout.TARGET_GET_ID_THRESHOLD);
    }
}
