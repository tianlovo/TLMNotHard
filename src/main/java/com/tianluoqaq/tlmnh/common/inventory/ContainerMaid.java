package com.tianluoqaq.tlmnh.common.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** 复用原版容器交易，每次操作重新检查主人、实体身份和距离 */
public final class ContainerMaid extends Container {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 220;
    public static final int ACTION_IDLE = 0;
    public static final int ACTION_ATTACK = 1;
    public static final int ACTION_RANGED = 2;
    public static final int ACTION_DAY = 3;
    public static final int ACTION_NIGHT = 4;
    public static final int ACTION_ALL_DAY = 5;
    public static final int ACTION_PICKUP_ITEMS = 6;
    public static final int ACTION_PICKUP_EXPERIENCE = 7;
    public static final int ACTION_SIT = 8;
    private final EntityMaid maid;
    private final EntityPlayer viewer;
    private int lastActionTick = Integer.MIN_VALUE;

    public ContainerMaid(InventoryPlayer playerInventory, EntityMaid maid) {
        this.maid = maid;
        this.viewer = playerInventory.player;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
            addMaidSlot(slot, 8 + slot * 18, 100);
        }
        addMaidSlot(MaidInventory.MAIN_HAND, 8, 28);
        addMaidSlot(MaidInventory.OFF_HAND, 26, 28);
        for (int index = 0; index < 4; index++) addMaidSlot(MaidInventory.ARMOR_START + index, 8 + index * 18, 64);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 140 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++)
            addSlotToContainer(new Slot(playerInventory, column, 8 + column * 18, 198));
    }

    private void addMaidSlot(int index, int x, int y) {
        addSlotToContainer(new Slot(maid.getMaidInventory(), index, x, y) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return inventory.isItemValidForSlot(index, stack);
            }

            @Override
            public int getSlotStackLimit() {
                return index >= MaidInventory.ARMOR_START ? 1 : super.getSlotStackLimit();
            }

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return canInteractWith(player);
            }
        });
    }

    public EntityMaid getMaid() {
        return maid;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return player == viewer && maid.getMaidInventory()
            .isUseableByPlayer(player) && player.worldObj.getEntityByID(maid.getEntityId()) == maid;
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        return canInteractWith(player) ? super.slotClick(slot, button, mode, player) : null;
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        if (maid.worldObj.isRemote || !canInteractWith(player)
            || action < ACTION_IDLE
            || action > ACTION_SIT
            || lastActionTick == maid.ticksExisted) return false;
        if (action == ACTION_SIT && maid.ridingEntity != null) return false;
        lastActionTick = maid.ticksExisted;
        switch (action) {
            case ACTION_IDLE -> maid.setTask(MaidTask.IDLE);
            case ACTION_ATTACK -> maid.setTask(MaidTask.ATTACK);
            case ACTION_RANGED -> maid.setTask(MaidTask.RANGED_ATTACK);
            case ACTION_DAY -> maid.setSchedule(MaidSchedule.DAY);
            case ACTION_NIGHT -> maid.setSchedule(MaidSchedule.NIGHT);
            case ACTION_ALL_DAY -> maid.setSchedule(MaidSchedule.ALL);
            case ACTION_PICKUP_ITEMS -> maid.setPickupItems(!maid.canPickupItems());
            case ACTION_PICKUP_EXPERIENCE -> maid.setPickupExperience(!maid.canPickupExperience());
            case ACTION_SIT -> {
                maid.setSitting(!maid.isSitting());
                maid.getNavigator()
                    .clearPathEntity();
                maid.setAttackTarget(null);
            }
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
        if (index < MaidInventory.TOTAL_SIZE) {
            mergeChecked(stack, MaidInventory.TOTAL_SIZE, inventorySlots.size(), true);
        } else {
            mergeChecked(stack, MaidInventory.ARMOR_START, MaidInventory.TOTAL_SIZE, false);
            if (stack.getItem() instanceof ItemBow || stack.getItem() instanceof ItemSword) {
                mergeChecked(stack, MaidInventory.MAIN_HAND, MaidInventory.MAIN_HAND + 1, false);
            }
            mergeChecked(stack, 0, MaidInventory.BACKPACK_SIZE, false);
        }
        if (stack.stackSize == before.stackSize) return null;
        if (stack.stackSize <= 0) slot.putStack(null);
        else slot.onSlotChanged();
        slot.onPickupFromSlot(player, stack);
        return before;
    }

    /** 原版 1.7.10 合并忽略目标槽限制，这里按有效性与容量逐个移动 */
    private void mergeChecked(ItemStack source, int start, int end, boolean reverse) {
        for (int pass = 0; pass < 2 && source.stackSize > 0; pass++) {
            for (int offset = 0; offset < end - start && source.stackSize > 0; offset++) {
                var slot = inventorySlots.get(reverse ? end - 1 - offset : start + offset);
                if (!slot.isItemValid(source)) continue;
                var existing = slot.getStack();
                int limit = Math.min(slot.getSlotStackLimit(), source.getMaxStackSize());
                if (pass == 0 && existing != null
                    && source.isStackable()
                    && source.isItemEqual(existing)
                    && ItemStack.areItemStackTagsEqual(source, existing)) {
                    int moved = Math.min(source.stackSize, Math.max(0, limit - existing.stackSize));
                    if (moved > 0) {
                        existing.stackSize += moved;
                        source.stackSize -= moved;
                        slot.onSlotChanged();
                    }
                } else if (pass == 1 && existing == null && limit > 0) {
                    slot.putStack(source.splitStack(Math.min(limit, source.stackSize)));
                }
            }
        }
    }
}
