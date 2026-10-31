/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/MaidMainContainer.java, MIT
 * Adaptation: native IInventory equipment, one slot per logical backpack entry
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public abstract class MaidMainContainer extends AbstractMaidContainer {

    protected MaidMainContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
        for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
            int armorType = row * 2 + col;
            addMaidSlot(MaidInventory.ARMOR_START + 3 - armorType, 94 + 20 * col, 37 + 20 * row, armorType);
        }
        addMaidSlot(MaidInventory.MAIN_HAND, 87, 77, -1);
        addMaidSlot(MaidInventory.OFF_HAND, 121, 77, -1);
        for (int i = 0; i < MaidInventory.BACKPACK_SIZE; i++) addMaidSlot(i, 143 + 18 * i, 37, -1);
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
                return canInteractWith(player);
            }

            @Override
            @SideOnly(Side.CLIENT)
            public IIcon getBackgroundIconIndex() {
                return armorType >= 0 ? ItemArmor.func_94602_b(armorType) : null;
            }
        });
    }
}
