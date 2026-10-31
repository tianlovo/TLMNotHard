/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/config/MaidConfigContainer.java, MIT
 * Adaptation: legacy player slots and server-authoritative relative frequency actions
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container.config;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;

public final class MaidConfigContainer extends AbstractMaidContainer {

    public static final int FREQUENCY_DOWN = 24, FREQUENCY_UP = 25;

    public MaidConfigContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        if (action != FREQUENCY_DOWN && action != FREQUENCY_UP) return super.enchantItem(player, action);
        if (maid.worldObj.isRemote || !canInteractWith(player) || player.inventory.getItemStack() != null) return false;
        var config = maid.getConfigManager();
        config.setSoundFreq(config.getSoundFreq() + (action == FREQUENCY_DOWN ? -0.1F : 0.1F));
        return true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (!canInteractWith(player) || index < 0 || index >= inventorySlots.size()) return null;
        var slot = inventorySlots.get(index);
        if (!slot.getHasStack()) return null;
        var stack = slot.getStack();
        var before = stack.copy();
        boolean moved = index < 27 ? mergeItemStack(stack, 27, 36, false) : mergeItemStack(stack, 0, 27, false);
        if (!moved) return null;
        if (stack.stackSize == 0) slot.putStack(null);
        else slot.onSlotChanged();
        slot.onPickupFromSlot(player, stack);
        return before;
    }
}
