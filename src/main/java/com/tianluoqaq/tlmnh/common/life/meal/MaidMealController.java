package com.tianluoqaq.tlmnh.common.life.meal;

import java.util.function.BooleanSupplier;

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
    private int handSlot, sourceSlot = -1, remaining, nextCheck;
    private boolean breathing, modernMeal;

    public MaidMealController(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        if (maid.worldObj.isRemote) return;
        if (!permitted(breathing)) {
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
        var policy = maid.getBrainCoordinator()
            .peekPolicyRuntime();
        // 现代组件由对应行为显式开始物品使用，此处只推进已持有的使用生命周期
        if (policy != null && policy.requiresAfterNativeNavigation()) return;
        if (maid.getBrainCoordinator()
            .getBreathController()
            .isActive()) return;
        if (nextCheck-- > 0 || maid.getItemUseState()
            .peek() != null) return;
        nextCheck = 50 + maid.getRNG()
            .nextInt(50);
        boolean healing = maid.getMaxHealth() - maid.getHealth() >= 2;
        if (!healing && !maid.getFavorabilityManager()
            .canAdd(com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type.WORK_MEAL.getTypeName())) return;
        for (int slot = MaidInventory.MAIN_HAND; slot <= MaidInventory.OFF_HAND; slot++)
            if (start(slot, healing)) return;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) if (start(slot, healing)) return;
    }

    private boolean start(int slot, boolean healing) {
        return start(slot, healing, false);
    }

    public boolean tryStartBreathing() {
        return tryStartBreathing(() -> true, false);
    }

    public boolean tryStartBreathing(BooleanSupplier current) {
        return tryStartBreathing(current, true);
    }

    private boolean tryStartBreathing(BooleanSupplier current, boolean request) {
        if (!permitted(true) || !current.getAsBoolean()) return false;
        if (lease != null && !maid.getItemUseState()
            .isCurrent(lease)) cancel();
        if (isEating()) return breathing;
        if (maid.getItemUseState()
            .peek() != null) return false;
        for (int slot = MaidInventory.MAIN_HAND; slot <= MaidInventory.OFF_HAND; slot++)
            if (start(slot, true, true, current)) return true;
        if (request) com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil
            .findStackSlot(maid, stack -> MaidFoodProfile.inspectBreathing(stack) != null, -1, current);
        if (!current.getAsBoolean()) return false;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++)
            if (start(slot, true, true, current)) return true;
        return false;
    }

    public boolean tryStartMeal(boolean healing, BooleanSupplier current) {
        if (!permitted(true) || !current.getAsBoolean()) return false;
        if (lease != null && !maid.getItemUseState()
            .isCurrent(lease)) cancel();
        var active = maid.getItemUseState()
            .peek();
        if (active != null && (active != lease || breathing)) return false;
        for (int slot = MaidInventory.MAIN_HAND; slot <= MaidInventory.OFF_HAND; slot++)
            if (startModernMeal(slot, healing, current)) return true;
        com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil
            .findStackSlot(maid, stack -> mealProfile(stack, healing) != null, -1, current);
        if (!current.getAsBoolean()) return false;
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++)
            if (startModernMeal(slot, healing, current)) return true;
        return false;
    }

    private MaidFoodProfile mealProfile(ItemStack stack, boolean healing) {
        return stack == null || MaidMealConfig.blocked(stack, healing) ? null : MaidFoodProfile.inspect(stack);
    }

    private boolean startModernMeal(int slot, boolean healing, BooleanSupplier current) {
        var selected = mealProfile(
            maid.getMaidInventory()
                .getStackInSlot(slot),
            healing);
        if (selected == null || !current.getAsBoolean()) return false;
        if (lease != null) {
            // 原startUsingItem在已使用时不重置期限，meal回调仍独立施加开始效果
            if (slot < MaidInventory.BACKPACK_SIZE) return false;
            applyStartEffect(selected, healing);
            return true;
        }
        modernMeal = true;
        if (start(slot, healing, false, current)) return true;
        modernMeal = false;
        return false;
    }

    private boolean start(int slot, boolean healing, boolean forBreathing) {
        return start(slot, healing, forBreathing, () -> true);
    }

    private boolean start(int slot, boolean healing, boolean forBreathing, BooleanSupplier current) {
        var inventory = maid.getMaidInventory();
        var stack = inventory.getStackInSlot(slot);
        if (stack == null || !forBreathing && MaidMealConfig.blocked(stack, healing)) return false;
        var selected = forBreathing ? MaidFoodProfile.inspectBreathing(stack) : MaidFoodProfile.inspect(stack);
        if (selected == null || !current.getAsBoolean() || inventory.getStackInSlot(slot) != stack) return false;
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
        breathing = forBreathing;
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
        applyStartEffect(profile, healing);
        return true;
    }

    private void applyStartEffect(MaidFoodProfile selected, boolean healing) {
        if (healing) {
            if (maid.getRNG()
                .nextInt(5) < selected.total) maid.heal(Math.max(selected.total / 5, 1));
        } else {
            int point = maid.getRNG()
                .nextInt(100) < selected.total ? 0 : 1;
            maid.getFavorabilityManager()
                .apply(com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type.WORK_MEAL, point);
        }
    }

    private boolean permitted(boolean breathingUse) {
        return maid.isEntityAlive()
            && !com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
            && !maid.hasPose(MaidPose.SLEEPING)
            && !maid.getBrainCoordinator()
                .isSuspended()
            && (breathingUse || modernMeal || maid.ridingEntity == null);
    }

    private boolean valid() {
        return permitted(breathing) && maid.getMaidInventory()
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
        breathing = false;
        modernMeal = false;
        inventory.markDirty();
    }

    public boolean isEating() {
        return lease != null && maid.getItemUseState()
            .isCurrent(lease);
    }

    public boolean isBreathing() {
        return breathing && isEating();
    }

    public void save(NBTTagCompound tag) {
        cancel();
    }

    public void load(NBTTagCompound tag) {
        nextCheck = 0;
    }
}
