package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

/** 试听仅在本机；应用音包使用现有容器交易，由服务端重新检查主人及窗口 */
public final class ContainerMaidSounds extends Container {

    private final EntityMaid maid;
    private final EntityPlayer viewer;
    private int lastActionTick = Integer.MIN_VALUE;

    public ContainerMaidSounds(InventoryPlayer inventory, EntityMaid maid) {
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
            || lastActionTick == maid.ticksExisted
            || action < 0
            || action >= BuiltinSoundCatalog.packs()
                .size())
            return false;
        lastActionTick = maid.ticksExisted;
        maid.setSoundPackId(
            BuiltinSoundCatalog.packs()
                .get(action).id);
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
