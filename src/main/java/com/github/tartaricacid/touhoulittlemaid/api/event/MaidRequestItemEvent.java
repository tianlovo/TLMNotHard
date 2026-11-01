/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/event/MaidRequestItemEvent.java, MIT
 * Adaptation: Forge 1.7.10 event and null empty-stack representation
 */
package com.github.tartaricacid.touhoulittlemaid.api.event;

import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.Cancelable;
import cpw.mods.fml.common.eventhandler.Event;

/** requestedItem仅是已转入真实库存的回执，事件本身不创建或插入物品 */
@Cancelable
public class MaidRequestItemEvent extends Event {

    private final EntityMaid maid;
    private final Predicate<ItemStack> itemFilter;
    private final int maxCount;
    private ItemStack requestedItem;

    public MaidRequestItemEvent(EntityMaid maid, Predicate<ItemStack> itemFilter, int maxCount) {
        this.maid = maid;
        this.itemFilter = itemFilter;
        this.maxCount = maxCount;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public Predicate<ItemStack> getItemFilter() {
        return itemFilter;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public ItemStack getRequestedItem() {
        return requestedItem;
    }

    public void setRequestedItem(ItemStack requestedItem) {
        this.requestedItem = requestedItem;
    }

    public boolean hasRequestedItem() {
        return requestedItem != null && requestedItem.stackSize > 0;
    }
}
