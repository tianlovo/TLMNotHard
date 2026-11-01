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
    public static final int PICK_TYPE_PREVIOUS = 26, PICK_TYPE_NEXT = 27, TOGGLE_DOOR = 28, TOGGLE_FENCE_GATE = 29,
        TOGGLE_CLIMBING = 30, TOGGLE_BACK_ITEM = 31, TOGGLE_CHAT_BUBBLE = 32;

    public MaidConfigContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        if (action < FREQUENCY_DOWN || action > TOGGLE_CHAT_BUBBLE) return super.enchantItem(player, action);
        if (maid.worldObj.isRemote || !canInteractWith(player) || player.inventory.getItemStack() != null) return false;
        var config = maid.getConfigManager();
        switch (action) {
            case FREQUENCY_DOWN -> config.setSoundFreq(config.getSoundFreq() - 0.1F);
            case FREQUENCY_UP -> config.setSoundFreq(config.getSoundFreq() + 0.1F);
            case PICK_TYPE_PREVIOUS -> config.setPickupType(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType
                    .getPreviousPickType(config.getPickupType()));
            case PICK_TYPE_NEXT -> config.setPickupType(
                com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType
                    .getNextPickType(config.getPickupType()));
            case TOGGLE_DOOR -> config.setOpenDoor(!config.isOpenDoor());
            case TOGGLE_FENCE_GATE -> config.setOpenFenceGate(!config.isOpenFenceGate());
            case TOGGLE_CLIMBING -> config.setActiveClimbing(!config.isActiveClimbing());
            case TOGGLE_CHAT_BUBBLE -> config.setShowChatBubble(!config.isChatBubbleShow());
            case TOGGLE_BACK_ITEM -> config.setShowBackItem(!config.isShowBackItem());
            default -> {
                return false;
            }
        }
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
