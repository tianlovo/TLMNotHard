/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/AbstractMaidContainer.java, MIT
 * Adaptation: 1.7.10 slots and shared server-authoritative menu actions
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.OriginalMaidTransfers;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

public abstract class AbstractMaidContainer extends ContainerMaid {

    protected final EntityMaid maid;
    private int effectCounter;
    private java.util.List<com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage.EffectData> displayedEffects = java.util.Collections
        .emptyList();
    private boolean displayedInvulnerable;

    protected AbstractMaidContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid, false);
        this.maid = maid;
        addPlayerInv(inventory);
    }

    protected void addPlayerInv(InventoryPlayer inventory) {
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlotToContainer(new Slot(inventory, col + row * 9 + 9, 88 + col * 18, 174 + row * 18));
        for (int col = 0; col < 9; col++) addSlotToContainer(new Slot(inventory, col, 88 + col * 18, 232));
    }

    @Override
    protected int modelGuiId() {
        return CommonProxy.GUI_MAID_ORIGINAL_MODELS;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return super.canInteractWith(player) && !maid.hasPose(MaidPose.SLEEPING);
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (maid.worldObj.isRemote || effectCounter++ % 20 != 0) return;
        for (Object listener : crafters) if (listener instanceof net.minecraft.entity.player.EntityPlayerMP viewer)
            com.tianluoqaq.tlmnh.common.network.MaidEffectNetwork.send(this, viewer);
    }

    public void setDisplayedEffects(
        java.util.List<com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage.EffectData> effects,
        boolean invulnerable) {
        displayedEffects = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(effects));
        displayedInvulnerable = invulnerable;
    }

    public java.util.List<com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage.EffectData> getDisplayedEffects() {
        return displayedEffects;
    }

    public boolean isDisplayedInvulnerable() {
        return displayedInvulnerable;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        return canInteractWith(player) ? OriginalMaidTransfers.transfer(this, player, index) : null;
    }
}
