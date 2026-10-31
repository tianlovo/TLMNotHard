/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: inventory/container/backpack/EmptyBackpackContainer.java, MIT
 * Adaptation: 1.7.10 GuiHandler registration; no additional backpack content
 */
package com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack;

import net.minecraft.entity.player.InventoryPlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.MaidMainContainer;

public final class EmptyBackpackContainer extends MaidMainContainer {

    public EmptyBackpackContainer(InventoryPlayer inventory, EntityMaid maid) {
        super(inventory, maid);
    }
}
