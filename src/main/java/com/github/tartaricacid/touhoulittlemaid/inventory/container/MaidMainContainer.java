/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/MaidMainContainer.java, MIT
 * Adaptation: native IInventory equipment, one slot per logical backpack entry, platform empty-hand icons
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.clienthook.HandSlotIconHook;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidMainLayout;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public abstract class MaidMainContainer extends AbstractMaidContainer {

    protected MaidMainContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
        for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
            int armorType = row * 2 + col;
            addMaidSlot(MaidInventoryLayout.ARMOR_START + 3 - armorType, 94 + 20 * col, 37 + 20 * row, armorType);
        }
        addMaidSlot(MaidInventoryLayout.MAIN_HAND, 87, 77, -1);
        addMaidSlot(MaidInventoryLayout.OFF_HAND, 121, 77, -1);
        addBackpackSlots();
    }

    protected void addBackpackSlots() {
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++) {
            int column = slot % MaidMainLayout.BACKPACK_COLUMNS;
            int row = slot / MaidMainLayout.BACKPACK_COLUMNS;
            addMaidSlot(
                slot,
                MaidMainLayout.BACKPACK_X + MaidMainLayout.CELL_STRIDE * column,
                MaidMainLayout.backpackY(row),
                -1);
        }
    }

    private void addMaidSlot(int index, int x, int y, int armorType) {
        addSlotToContainer(new Slot(maid.getMaidInventory(), index, x, y) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return inventory.isItemValidForSlot(index, stack);
            }

            @Override
            public int getSlotStackLimit() {
                return armorType >= 0 ? 1 : super.getSlotStackLimit();
            }

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return canModifyWith(player);
            }

            @Override
            @SideOnly(Side.CLIENT)
            public IIcon getBackgroundIconIndex() {
                if (armorType >= 0) return ItemArmor.func_94602_b(armorType);
                if (index == MaidInventoryLayout.MAIN_HAND || index == MaidInventoryLayout.OFF_HAND)
                    return HandSlotIconHook.iconForHand(index == MaidInventoryLayout.OFF_HAND);
                return null;
            }
        });
    }
}
