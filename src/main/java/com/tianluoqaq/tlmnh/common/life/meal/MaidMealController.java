package com.tianluoqaq.tlmnh.common.life.meal;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.config.MaidMealConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

/** 原版回血/日常进食时序，临时换手用背包原槽交换，不另藏一份物品 */
public final class MaidMealController {

    private final EntityMaid maid;
    private MaidItemUseState.Lease lease;
    private MaidFoodProfile profile;
    private ItemStack food, expected, displaced;
    private int handSlot, sourceSlot = -1, remaining, nextCheck, workCooldown;

    public MaidMealController(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        if (maid.worldObj.isRemote) return;
        if (workCooldown > 0) workCooldown--;
        if (!permitted()) {
            cancel();
            return;
        }
        if (lease != null) {
            if (!maid.getItemUseState()
                .isCurrent(lease)) {
                cancel();
                return;
            }
            if (--remaining <= 0) finish();
            return;
        }
        if (nextCheck-- > 0 || maid.getItemUseState()
            .peek() != null) return;
        nextCheck = 50 + maid.getRNG()
            .nextInt(50);
        boolean healing = maid.getMaxHealth() - maid.getHealth() >= 2;
        if (!healing && workCooldown > 0) return;
        for (int slot = MaidInventory.MAIN_HAND; slot <= MaidInventory.OFF_HAND; slot++)
            if (start(slot, healing)) return;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) if (start(slot, healing)) return;
    }

    private boolean start(int slot, boolean healing) {
        var inventory = maid.getMaidInventory();
        var stack = inventory.getStackInSlot(slot);
        if (stack == null || MaidMealConfig.blocked(stack, healing)) return false;
        var selected = MaidFoodProfile.inspect(stack);
        if (selected == null) return false;
        handSlot = slot;
        sourceSlot = -1;
        if (slot < MaidInventory.BACKPACK_SIZE) {
            sourceSlot = slot;
            handSlot = inventory.getStackInSlot(MaidInventory.MAIN_HAND) == null ? MaidInventory.MAIN_HAND
                : MaidInventory.OFF_HAND;
            displaced = inventory.getStackInSlot(handSlot);
            inventory.setInventorySlotContents(sourceSlot, displaced);
            inventory.setInventorySlotContents(handSlot, stack);
        }
        food = stack;
        expected = stack.copy();
        profile = selected;
        remaining = profile.duration;
        lease = maid.getItemUseState()
            .tryAcquire(
                this,
                food,
                handSlot == MaidInventory.OFF_HAND ? MaidItemUseState.Hand.OFF : MaidItemUseState.Hand.MAIN,
                MaidItemUseState.Kind.FOOD,
                this::valid);
        if (lease == null) {
            cancel();
            return false;
        }
        // 上游在开始使用时回血/计好感，不把它改成另一套饥饿值系统
        if (healing) {
            if (maid.getRNG()
                .nextInt(5) < profile.total) maid.heal(Math.max(profile.total / 5, 1));
        } else {
            int point = maid.getRNG()
                .nextInt(100) < profile.total ? 0 : 1;
            maid.setFavorability(maid.getFavorability() + point);
            workCooldown = 3 * 60 * 20;
            if (point > 0) maid.worldObj.setEntityState(maid, (byte) 7);
        }
        return true;
    }

    private boolean permitted() {
        return maid.isEntityAlive()
            && !com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            && !maid.hasPose(MaidPose.SLEEPING)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && maid.ridingEntity == null;
    }

    private boolean valid() {
        return permitted() && maid.getMaidInventory()
            .getStackInSlot(handSlot) == food && ItemStack.areItemStacksEqual(food, expected);
    }

    private void finish() {
        if (!valid()) {
            cancel();
            return;
        }
        var inventory = maid.getMaidInventory();
        var completed = profile;
        food.stackSize--;
        expected.stackSize--;
        if (food.stackSize == 0) inventory.setInventorySlotContents(handSlot, null);
        cancel();
        var container = completed.container();
        if (container != null) {
            var leftover = inventory.insertAvailable(container, false);
            if (leftover != null) maid.entityDropItem(leftover, 0);
        }
        completed.applyEffects(maid);
        maid.playSound(
            "random.burp",
            .5F,
            maid.getRNG()
                .nextFloat() * .1F + .9F);
    }

    /** 双端菜单改过任何交换槽时不覆盖新物品；所有物品始终在真实库存内 */
    public void cancel() {
        if (lease == null && food == null) return;
        var oldLease = lease;
        lease = null;
        maid.getItemUseState()
            .release(oldLease);
        var inventory = maid.getMaidInventory();
        if (sourceSlot >= 0 && inventory.getStackInSlot(sourceSlot) == displaced) {
            var current = inventory.getStackInSlot(handSlot);
            if (current == food || current == null && food != null && food.stackSize == 0) {
                inventory.setInventorySlotContents(sourceSlot, current);
                inventory.setInventorySlotContents(handSlot, displaced);
            }
        }
        sourceSlot = -1;
        food = expected = displaced = null;
        profile = null;
        remaining = 0;
        inventory.markDirty();
    }

    public boolean isEating() {
        return lease != null && maid.getItemUseState()
            .isCurrent(lease);
    }

    public void save(NBTTagCompound tag) {
        cancel();
        tag.setInteger("TLMNHWorkMealCooldown", workCooldown);
    }

    public void load(NBTTagCompound tag) {
        workCooldown = Math.max(0, Math.min(3600, tag.getInteger("TLMNHWorkMealCooldown")));
        nextCheck = 0;
    }
}
