/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/backpack/BaubleContainer.java, MIT
 * Adaptation: Minecraft 1.7.10 inventory and server-authoritative slots
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.MaidMainContainer;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;

public final class BaubleContainer extends MaidMainContainer {

    public BaubleContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
    }

    @Override
    protected void addBackpackSlots() {
        int unlocked = MaidBaubleLayout.unlockedSlots(
            maid.getFavorabilityManager()
                .getLevel());
        for (int slot = 0; slot < unlocked; slot++) {
            int column = slot % MaidBaubleLayout.COLUMNS;
            int row = slot / MaidBaubleLayout.COLUMNS;
            addSlotToContainer(
                new Slot(
                    maid.getMaidBaubleInventory(),
                    slot,
                    MaidBaubleLayout.SLOT_X + MaidBaubleLayout.CELL_STRIDE * column,
                    MaidBaubleLayout.SLOT_Y + MaidBaubleLayout.CELL_STRIDE * row) {

                    @Override
                    public boolean isItemValid(ItemStack stack) {
                        return inventory.isItemValidForSlot(getSlotIndex(), stack);
                    }

                    @Override
                    public boolean canTakeStack(EntityPlayer player) {
                        return canModifyWith(player);
                    }
                });
        }
    }
}
