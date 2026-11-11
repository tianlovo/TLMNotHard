package com.tianluoqaq.tlmnh.common.inventory.container;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.ChatComponentTranslation;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.inventory.MaidContainerAccess;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.MaidTaskEquipment;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxyProtocol;

/** 复用原版容器交易，每次操作重新检查主人、实体身份和距离 */
public class ContainerMaid extends Container {

    private final EntityMaid maid;
    private final EntityPlayer viewer;
    private int lastActionTick = Integer.MIN_VALUE;

    public ContainerMaid(InventoryPlayer playerInventory, EntityMaid maid) {
        this(playerInventory, maid, true);
    }

    /** 原版布局复用相同权限和动作处理，槽位由原版容器子类建立 */
    protected ContainerMaid(InventoryPlayer playerInventory, EntityMaid maid, boolean developmentLayout) {
        this.maid = maid;
        this.viewer = playerInventory.player;
        maid.openMaidContainer(this);
        if (!developmentLayout) return;
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++) {
            addMaidSlot(
                slot,
                MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM
                    + (slot % MaidContainerLayout.MIN_COLUMN_THRESHOLD) * MaidContainerLayout.SLOT_CELL_STRIDE,
                MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_THIRD_PARAM
                    + (slot / MaidContainerLayout.MIN_COLUMN_THRESHOLD) * MaidContainerLayout.SLOT_CELL_STRIDE);
        }
        addMaidSlot(
            MaidInventoryLayout.MAIN_HAND,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_THIRD_PARAM_2);
        addMaidSlot(
            MaidInventoryLayout.OFF_HAND,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM_2,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_THIRD_PARAM_2);
        for (int index = 0; index < MaidContainerLayout.MIN_INDEX_THRESHOLD; index++) addMaidSlot(
            MaidInventoryLayout.ARMOR_START + index,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM + index * MaidContainerLayout.SLOT_CELL_STRIDE,
            MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_THIRD_PARAM_3);
        for (int row = 0; row < MaidContainerLayout.MIN_ROW_THRESHOLD; row++) {
            for (int column = 0; column < MaidContainerLayout.MIN_COLUMN_THRESHOLD; column++) {
                addSlotToContainer(
                    new Slot(
                        playerInventory,
                        column + row * MaidContainerLayout.MIN_COLUMN_THRESHOLD
                            + MaidContainerLayout.MIN_COLUMN_THRESHOLD,
                        MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM
                            + column * MaidContainerLayout.SLOT_CELL_STRIDE,
                        MaidContainerLayout.CONTAINER_SLOT_Y + row * MaidContainerLayout.SLOT_CELL_STRIDE));
            }
        }
        for (int column = 0; column < MaidContainerLayout.MIN_COLUMN_THRESHOLD; column++) addSlotToContainer(
            new Slot(
                playerInventory,
                column,
                MaidContainerLayout.CONTAINER_ADD_MAID_SLOT_SECOND_PARAM
                    + column * MaidContainerLayout.SLOT_CELL_STRIDE,
                MaidContainerLayout.CONTAINER_SLOT_Y_2));
    }

    private void addMaidSlot(int index, int x, int y) {
        addSlotToContainer(new Slot(maid.getMaidInventory(), index, x, y) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return inventory.isItemValidForSlot(index, stack);
            }

            @Override
            public int getSlotStackLimit() {
                return index >= MaidInventoryLayout.ARMOR_START
                    ? MaidContainerLayout.CONTAINER_ADD_SLOT_TO_CONTAINER_UNIT_STEP
                    : super.getSlotStackLimit();
            }

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return canModifyWith(player);
            }
        });
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        maid.closeMaidContainer(this);
    }

    public EntityMaid getMaid() {
        return maid;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return MaidContainerAccess.canInteract(viewer, player, maid);
    }

    /** 保留原版子类的睡眠等会话限制，再核真正写操作权限 */
    public final boolean canModifyWith(EntityPlayer player) {
        return canInteractWith(player) && !maid.getCombatHitReceipt()
            .blocksTransfer() && MaidContainerAccess.canModify(viewer, player, maid);
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        return canModifyWith(player) ? super.slotClick(slot, button, mode, player) : null;
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int action) {
        if (maid.worldObj.isRemote || !canInteractWith(player)
            || action < MaidContainerProtocol.ACTION_IDLE
            || action > MaidContainerProtocol.ACTION_BAUBLE
            || lastActionTick == maid.ticksExisted) return false;
        if (!isReadOnlyNavigation(action) && !canModifyWith(player)) return false;
        if (action <= MaidContainerProtocol.ACTION_RANGED && !maid.getCombatTaskState()
            .canSelect()) {
            player.addChatMessage(new ChatComponentTranslation(MaidContainerIds.I18N_MESSAGE_TLMNH_COMBAT_FUTURE_TASK));
            return false;
        }
        if (action == MaidContainerProtocol.ACTION_SIT && maid.ridingEntity != null) return false;
        if (action == MaidContainerProtocol.ACTION_RIDEABLE && player.inventory.getItemStack() != null) return false;
        if ((action == MaidContainerProtocol.ACTION_MODELS || action == MaidContainerProtocol.ACTION_SOUNDS
            || action == MaidContainerProtocol.ACTION_CONFIG
            || action == MaidContainerProtocol.ACTION_TASK_CONFIG
            || action == MaidContainerProtocol.ACTION_ORIGINAL_MAIN
            || action == MaidContainerProtocol.ACTION_BAUBLE) && player.inventory.getItemStack() != null) {
            player.addChatMessage(new ChatComponentTranslation(MaidContainerIds.I18N_GUI_TLMNH_MODELS_PUT_DOWN_ITEM));
            return false;
        }
        lastActionTick = maid.ticksExisted;
        switch (action) {
            case MaidContainerProtocol.ACTION_IDLE -> maid.setTask(MaidTask.IDLE);
            case MaidContainerProtocol.ACTION_ATTACK -> {
                maid.setTask(MaidTask.ATTACK);
                if (!maid.isUnifiedCombat()) MaidTaskEquipment.equip(player, maid, MaidTask.ATTACK);
            }
            case MaidContainerProtocol.ACTION_RANGED -> {
                maid.setTask(MaidTask.RANGED_ATTACK);
                if (!maid.isUnifiedCombat()) MaidTaskEquipment.equip(player, maid, MaidTask.RANGED_ATTACK);
            }
            case MaidContainerProtocol.ACTION_DAY -> maid.setSchedule(MaidSchedule.DAY);
            case MaidContainerProtocol.ACTION_NIGHT -> maid.setSchedule(MaidSchedule.NIGHT);
            case MaidContainerProtocol.ACTION_ALL_DAY -> maid.setSchedule(MaidSchedule.ALL);
            case MaidContainerProtocol.ACTION_PICKUP_ALL -> {
                maid.setPickup(!maid.isPickup());
            }
            case MaidContainerProtocol.ACTION_PICKUP_ITEMS -> maid.setPickupItems(!maid.canPickupItems());
            case MaidContainerProtocol.ACTION_PICKUP_EXPERIENCE -> maid
                .setPickupExperience(!maid.canPickupExperience());
            case MaidContainerProtocol.ACTION_RIDEABLE -> {
                maid.setRideable(!maid.isRideable());
                if (!maid.isRideable() && maid.ridingEntity != null) maid.mountEntity(null);
            }
            case MaidContainerProtocol.ACTION_SIT -> {
                maid.setSitting(!maid.isSitting());
                maid.getNavigator()
                    .clearPathEntity();
                maid.setAttackTarget(null);
            }
            case MaidContainerProtocol.ACTION_MODELS -> player.openGui(
                ModContext.modInstance(),
                modelGuiId(),
                maid.worldObj,
                maid.getEntityId(),
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE,
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE);
            case MaidContainerProtocol.ACTION_SOUNDS -> player.openGui(
                ModContext.modInstance(),
                CommonProxyProtocol.GUI_MAID_SOUNDS,
                maid.worldObj,
                maid.getEntityId(),
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE,
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE);
            case MaidContainerProtocol.ACTION_CONFIG -> player.openGui(
                ModContext.modInstance(),
                CommonProxyProtocol.GUI_MAID_CONFIG,
                maid.worldObj,
                maid.getEntityId(),
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE,
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE);
            case MaidContainerProtocol.ACTION_ORIGINAL_MAIN -> player.openGui(
                ModContext.modInstance(),
                CommonProxyProtocol.GUI_MAID_ORIGINAL,
                maid.worldObj,
                maid.getEntityId(),
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE,
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE);
            case MaidContainerProtocol.ACTION_TASK_CONFIG -> openTaskConfiguration(player);
            case MaidContainerProtocol.ACTION_BAUBLE -> player.openGui(
                ModContext.modInstance(),
                CommonProxyProtocol.GUI_MAID_BAUBLE,
                maid.worldObj,
                maid.getEntityId(),
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE,
                MaidContainerLayout.OPEN_GUI_INITIAL_VALUE);
            case MaidContainerProtocol.ACTION_HOME -> {
                return maid.getHomeArea()
                    .setEnabled(
                        !maid.getHomeArea()
                            .enabled());
            }
            case MaidContainerProtocol.ACTION_HOME_WORK -> {
                return maid.getHomeArea()
                    .capture(Activity.WORK);
            }
            case MaidContainerProtocol.ACTION_HOME_IDLE -> {
                return maid.getHomeArea()
                    .capture(Activity.IDLE);
            }
            case MaidContainerProtocol.ACTION_HOME_REST -> {
                return maid.getHomeArea()
                    .capture(Activity.REST);
            }
            case MaidContainerProtocol.ACTION_HOME_RESET -> {
                return maid.getHomeArea()
                    .resetHere();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean isReadOnlyNavigation(int action) {
        return action == MaidContainerProtocol.ACTION_MODELS || action == MaidContainerProtocol.ACTION_SOUNDS
            || action == MaidContainerProtocol.ACTION_CONFIG
            || action == MaidContainerProtocol.ACTION_ORIGINAL_MAIN
            || action == MaidContainerProtocol.ACTION_BAUBLE
            || action == MaidContainerProtocol.ACTION_TASK_CONFIG;
    }

    protected int modelGuiId() {
        return CommonProxyProtocol.GUI_MAID_MODELS;
    }

    protected void openTaskConfiguration(EntityPlayer player) {
        long session;
        do {
            session = ThreadLocalRandom.current()
                .nextLong();
        } while (session == 0);
        player.openGui(
            ModContext.modInstance(),
            maid.getTask() == MaidTask.IDLE ? CommonProxyProtocol.GUI_MAID_DEFAULT_TASK_CONFIG
                : CommonProxyProtocol.GUI_MAID_ATTACK_CONFIG,
            maid.worldObj,
            maid.getEntityId(),
            (int) (session >>> MaidContainerLayout.UPPER_WORD_SHIFT),
            (int) session);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (!canModifyWith(player) || index < 0 || index >= inventorySlots.size()) return null;
        var slot = inventorySlots.get(index);
        if (!slot.getHasStack()) return null;
        var stack = slot.getStack();
        var before = stack.copy();
        if (index < MaidInventoryLayout.TOTAL_SIZE) {
            mergeChecked(stack, MaidInventoryLayout.TOTAL_SIZE, inventorySlots.size(), true);
        } else {
            mergeChecked(stack, MaidInventoryLayout.ARMOR_START, MaidInventoryLayout.TOTAL_SIZE, false);
            if (stack.getItem() instanceof ItemBow || stack.getItem() instanceof ItemSword) {
                mergeChecked(stack, MaidInventoryLayout.MAIN_HAND, MaidInventoryLayout.MAIN_HAND + 1, false);
            }
            mergeChecked(stack, MaidContainerLayout.OPEN_GUI_INITIAL_VALUE, MaidInventoryLayout.BACKPACK_SIZE, false);
        }
        if (stack.stackSize == before.stackSize) return null;
        if (stack.stackSize <= 0) slot.putStack(null);
        else slot.onSlotChanged();
        slot.onPickupFromSlot(player, stack);
        return before;
    }

    /** 原版 1.7.10 合并忽略目标槽限制，这里按有效性与容量逐个移动 */
    private void mergeChecked(ItemStack source, int start, int end, boolean reverse) {
        for (int pass = 0; pass < MaidContainerLayout.MIN_PASS_THRESHOLD && source.stackSize > 0; pass++) {
            for (int offset = 0; offset < end - start && source.stackSize > 0; offset++) {
                var slot = inventorySlots.get(reverse ? end - 1 - offset : start + offset);
                if (!slot.isItemValid(source)) continue;
                var existing = slot.getStack();
                int limit = Math.min(slot.getSlotStackLimit(), source.getMaxStackSize());
                if (pass == 0 && existing != null
                    && source.isStackable()
                    && source.isItemEqual(existing)
                    && ItemStack.areItemStackTagsEqual(source, existing)) {
                    int moved = Math.min(
                        source.stackSize,
                        Math.max(MaidContainerLayout.OPEN_GUI_INITIAL_VALUE, limit - existing.stackSize));
                    if (moved > 0) {
                        existing.stackSize += moved;
                        source.stackSize -= moved;
                        slot.onSlotChanged();
                    }
                } else if (pass == MaidContainerLayout.CONTAINER_ADD_SLOT_TO_CONTAINER_UNIT_STEP && existing == null
                    && limit > 0) {
                        slot.putStack(source.splitStack(Math.min(limit, source.stackSize)));
                    }
            }
        }
    }
}
