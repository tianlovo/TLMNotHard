package com.tianluoqaq.tlmnh.common.life.meal;

import java.util.function.BooleanSupplier;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil;
import com.tianluoqaq.tlmnh.common.ai.MaidBrainLifecycle;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.config.MaidMealConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.protection.recovery.ProtectedItemCodec;

/** 原版回血/日常进食时序，临时换手用背包原槽交换，不另藏一份物品 */
public final class MaidMealController {

    private final EntityMaid maid;
    private MaidItemUseState.Lease lease;
    private MaidFoodProfile profile;
    private ItemStack food, expected, displaced, displacedExpected;
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
            if (profile != null && !profile.available()) {
                cancel();
                return;
            }
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
        if (policy != null && (policy.getProfile() != MaidBrainProfile.LEGACY_PROTOTYPE || !policy.isReady())) return;
        if (maid.getBrainCoordinator()
            .getBreathController()
            .isActive()) return;
        if (nextCheck-- > 0 || maid.getItemUseState()
            .peek() != null) return;
        nextCheck = 50 + maid.getRNG()
            .nextInt(50);
        boolean healing = maid.getMaxHealth() - maid.getHealth() >= 2;
        if (!healing && !maid.getFavorabilityManager()
            .canAdd(Type.WORK_MEAL.getTypeName())) return;
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
        if (request)
            ItemsUtil.findStackSlot(maid, stack -> MaidFoodProfile.inspectBreathing(stack) != null, -1, current);
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
        ItemsUtil.findStackSlot(maid, stack -> mealProfile(stack, healing) != null, -1, current);
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
            displacedExpected = displaced == null ? null : displaced.copy();
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
                .apply(Type.WORK_MEAL, point);
        }
    }

    private boolean permitted(boolean breathingUse) {
        return maid.isEntityAlive() && !MaidBrainLifecycle.isWorldUnloading(maid.worldObj)
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
        food = expected = displaced = displacedExpected = null;
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
        var capturedLease = lease;
        var capturedFood = food;
        var capturedDisplaced = displaced;
        int source = sourceSlot, hand = handSlot;
        if (!ownsExchange(capturedLease, capturedFood, capturedDisplaced, source, hand)) return;

        // 原生NBT既用于持久保存，也用于外部只读查询；只归位输出副本，不终止活体使用
        var backpack = tag.getTagList("MaidInventory", Constants.NBT.TAG_COMPOUND);
        if (!tag.hasKey("MaidInventory", Constants.NBT.TAG_LIST)
            || backpack.tagCount() != ((NBTTagList) tag.getTag("MaidInventory")).tagCount()
            || backpack.func_150303_d() != 0 && backpack.func_150303_d() != 10) return;
        var sourceTag = new NBTTagCompound();
        int previous = -1;
        for (int i = 0; i < backpack.tagCount(); i++) {
            var entry = backpack.getCompoundTagAt(i);
            if (!entry.hasKey("Slot", Constants.NBT.TAG_BYTE)) return;
            int slot = entry.getByte("Slot") & 255;
            if (slot <= previous || slot >= MaidInventory.BACKPACK_SIZE) return;
            previous = slot;
            if (slot == source) {
                sourceTag = (NBTTagCompound) entry.copy();
                sourceTag.removeTag("Slot");
            }
        }
        var equipment = tag.getTagList("Equipment", Constants.NBT.TAG_COMPOUND);
        if (!tag.hasKey("Equipment", Constants.NBT.TAG_LIST) || equipment.tagCount() < 1
            || equipment.func_150303_d() != 10
            || equipment.tagCount() != ((NBTTagList) tag.getTag("Equipment")).tagCount()) return;
        var handTag = hand == MaidInventory.MAIN_HAND ? equipment.getCompoundTagAt(0)
            : tag.getCompoundTag("TLMNHOffhand");
        var foodTag = ProtectedItemCodec.encode(expected);
        var displacedTag = capturedDisplaced == null ? new NBTTagCompound()
            : ProtectedItemCodec.encode(displacedExpected);
        if (!handTag.equals(foodTag) || !sourceTag.equals(displacedTag)
            || !ownsExchange(capturedLease, capturedFood, capturedDisplaced, source, hand)) return;

        var restoredFood = (NBTTagCompound) handTag.copy();
        restoredFood.setByte("Slot", (byte) source);
        var restoredBackpack = new NBTTagList();
        boolean inserted = false;
        for (int i = 0; i < backpack.tagCount(); i++) {
            var entry = backpack.getCompoundTagAt(i);
            int slot = entry.getByte("Slot") & 255;
            if (!inserted && slot >= source) {
                restoredBackpack.appendTag(restoredFood);
                inserted = true;
            }
            if (slot != source) restoredBackpack.appendTag(entry.copy());
        }
        if (!inserted) restoredBackpack.appendTag(restoredFood);
        tag.setTag("MaidInventory", restoredBackpack);
        if (hand == MaidInventory.MAIN_HAND) {
            var restoredEquipment = new NBTTagList();
            for (int i = 0; i < equipment.tagCount(); i++) restoredEquipment.appendTag(
                i == 0 ? sourceTag.copy()
                    : equipment.getCompoundTagAt(i)
                        .copy());
            tag.setTag("Equipment", restoredEquipment);
        } else if (sourceTag.hasNoTags()) tag.removeTag("TLMNHOffhand");
        else tag.setTag("TLMNHOffhand", sourceTag.copy());
    }

    private boolean ownsExchange(MaidItemUseState.Lease capturedLease, ItemStack capturedFood,
        ItemStack capturedDisplaced, int source, int hand) {
        var inventory = maid.getMaidInventory();
        return capturedLease != null && capturedFood != null
            && expected != null
            && capturedFood.stackSize > 0
            && lease == capturedLease
            && food == capturedFood
            && displaced == capturedDisplaced
            && sourceSlot == source
            && handSlot == hand
            && source >= 0
            && source < MaidInventory.BACKPACK_SIZE
            && (hand == MaidInventory.MAIN_HAND || hand == MaidInventory.OFF_HAND)
            && maid.getItemUseState()
                .peek() == capturedLease
            && inventory.getStackInSlot(hand) == capturedFood
            && inventory.getStackInSlot(source) == capturedDisplaced
            && ItemStack.areItemStacksEqual(capturedFood, expected)
            && ItemStack.areItemStacksEqual(capturedDisplaced, displacedExpected);
    }

    public void load(NBTTagCompound tag) {
        nextCheck = 0;
    }
}
