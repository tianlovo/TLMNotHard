package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelSelection;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

/** 独立无物品槽窗口，避免模型索引与主界面动作混用 */
public final class ContainerMaidModels extends Container {

    private final EntityMaid maid;
    private final EntityPlayer viewer;
    private int lastActionTick = Integer.MIN_VALUE;

    public ContainerMaidModels(InventoryPlayer inventory, EntityMaid maid) {
        this.viewer = inventory.player;
        this.maid = maid;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return MaidContainerAccess.canInteract(viewer, player, maid);
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        if (maid.worldObj.isRemote || !canInteractWith(player)
            || player.inventory.getItemStack() != null
            || lastActionTick == maid.ticksExisted) return false;
        int index = BuiltinModelSelection.decodeAction(action);
        if (index == BuiltinModelSelection.BACK) {
            lastActionTick = maid.ticksExisted;
            player.openGui(TLMNotHard.INSTANCE, CommonProxy.GUI_MAID, maid.worldObj, maid.getEntityId(), 0, 0);
            return true;
        }
        var choice = BuiltinModelSelection.choiceForAction(action);
        if (choice == null || !BuiltinModelCatalog.contains(choice.getId())) return false;
        lastActionTick = maid.ticksExisted;
        maid.setModelId(choice.getId());
        return true;
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        return null;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        return null;
    }
}
