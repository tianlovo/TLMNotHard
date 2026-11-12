package com.tianluoqaq.tlmnh.common.life.hazard;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;

import com.github.tartaricacid.touhoulittlemaid.datapack.KaomojiData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.hazard.spi.HotItemHazardCompatProvider;

/**
 * 女仆携带热锭烫伤与丢弃反馈控制器：
 * 仅扫描女仆自身裸槽（双手与 36 格背包），嵌套背包内部容器天然免烫；
 * 累计 3 次真实扣血烫伤后主动丢弃全部裸露热物品、触发文字与表情双气泡，并开启短时防重捡保护
 */
public final class MaidHotItemHazardController {

    private int burnCount;
    private int pickupCooldownTicks;

    public int getBurnCount() {
        return burnCount;
    }

    public int getPickupCooldownTicks() {
        return pickupCooldownTicks;
    }

    public void tick(EntityMaid maid, HotItemHazardCompatProvider provider) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return;
        if (pickupCooldownTicks > 0) {
            pickupCooldownTicks--;
        }
        if (maid.ticksExisted <= 0) return;
        boolean periodic = maid.ticksExisted % HotItemHazardTuning.SCAN_PERIOD_TICKS == 0;
        if (periodic) evaluateNow(maid, provider);
        else if (provider != null && provider.isAvailable()) {
            var inv = maid.getMaidInventory();
            for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
                if (provider.perTickItemHeatDamage(inv.getStackInSlot(slot)) > 0) {
                    evaluate(maid, provider, true);
                    break;
                }
            }
        }
    }

    /** 立即执行一次裸露热物品扫描与结算（供周期脉冲及受控测试复用） */
    public boolean evaluateNow(EntityMaid maid, HotItemHazardCompatProvider provider) {
        return evaluate(maid, provider, false);
    }

    private boolean evaluate(EntityMaid maid, HotItemHazardCompatProvider provider, boolean perTickOnly) {
        if (maid == null || maid.worldObj == null
            || maid.worldObj.isRemote
            || !maid.isEntityAlive()
            || maid.getCombatHitReceipt()
                .blocksTransfer())
            return false;
        HotItemHazardCompatProvider active = provider != null ? provider : HotItemHazardCompatProvider.NOOP;
        if (!active.isAvailable()) {
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            return false;
        }

        MaidInventory inv = maid.getMaidInventory();
        float maxHeat = 0.0F;
        ItemStack hottest = null;
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0) continue;
            float dmg = perTickOnly ? active.perTickItemHeatDamage(stack) : active.inspectItemHeatDamage(stack);
            if (dmg > maxHeat) {
                maxHeat = dmg;
                hottest = stack;
            }
        }

        if (maxHeat <= 0.0F || hottest == null) {
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            return false;
        }

        if (hasHeatImmunity(maid, active)) {
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            return false;
        }

        float healthBefore = maid.getHealth();
        boolean accepted = active.applyHotItemDamage(maid, maxHeat, hottest);
        if (!maid.isEntityAlive()) {
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            return false;
        }
        if (maid.getHealth() >= healthBefore) {
            if (accepted || !perTickOnly) burnCount = HotItemHazardTuning.ZERO_COUNT;
            return false;
        }

        burnCount++;
        if (burnCount >= HotItemHazardTuning.BURN_COUNT_BEFORE_DROP) {
            if (!dropAllBareHotItems(maid, inv, active)) return false;
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            pickupCooldownTicks = HotItemHazardTuning.PICKUP_COOLDOWN_TICKS;
            maid.getChatBubbleManager()
                .addChatBubble(
                    TextChatBubbleData.create(
                        HotItemHazardTuning.BUBBLE_EXIST_TICKS,
                        HotItemHazardNbtKeys.I18N_HOT_INGOT_DROP,
                        HotItemHazardTuning.BUBBLE_PRIORITY));
            KaomojiData.showHurtKaomoji(maid, maid.getChatBubbleManager());
            return true;
        }
        return false;
    }

    /** 判断防重捡冷却期内是否应拒绝将地面热锭直接捡回自身裸槽（若可存入嵌套背包或已穿防护服则放行） */
    public boolean shouldBlockBareHotPickup(EntityMaid maid, ItemStack candidate,
        HotItemHazardCompatProvider provider) {
        if (pickupCooldownTicks <= 0 || maid == null || candidate == null || candidate.stackSize <= 0) return false;
        HotItemHazardCompatProvider active = provider != null ? provider : HotItemHazardCompatProvider.NOOP;
        if (!active.isAvailable() || active.inspectItemHeatDamage(candidate) <= 0.0F) return false;
        if (hasHeatImmunity(maid, active)) return false;
        ItemStack afterBackpacks = maid.getMaidInventory()
            .insertIntoBackpacks(candidate, true);
        return afterBackpacks != null && afterBackpacks.stackSize > 0;
    }

    public void readFromNBT(NBTTagCompound root) {
        if (root == null || !root.hasKey(HotItemHazardNbtKeys.ROOT_TAG, HotItemHazardTuning.NBT_TAG_COMPOUND)) {
            burnCount = HotItemHazardTuning.ZERO_COUNT;
            pickupCooldownTicks = HotItemHazardTuning.ZERO_COUNT;
            return;
        }
        NBTTagCompound sub = root.getCompoundTag(HotItemHazardNbtKeys.ROOT_TAG);
        burnCount = Math.max(HotItemHazardTuning.ZERO_COUNT, sub.getInteger(HotItemHazardNbtKeys.BURN_COUNT));
        pickupCooldownTicks = Math
            .max(HotItemHazardTuning.ZERO_COUNT, sub.getInteger(HotItemHazardNbtKeys.PICKUP_COOLDOWN));
    }

    public void writeToNBT(NBTTagCompound root) {
        if (root == null || (burnCount <= 0 && pickupCooldownTicks <= 0)) return;
        NBTTagCompound sub = new NBTTagCompound();
        sub.setInteger(HotItemHazardNbtKeys.BURN_COUNT, burnCount);
        sub.setInteger(HotItemHazardNbtKeys.PICKUP_COOLDOWN, pickupCooldownTicks);
        root.setTag(HotItemHazardNbtKeys.ROOT_TAG, sub);
    }

    private static boolean hasHeatImmunity(EntityMaid maid, HotItemHazardCompatProvider active) {
        return maid.isImmuneToFire() || maid.isPotionActive(Potion.fireResistance)
            || active.hasFullHeatProtection(maid);
    }

    private static boolean dropAllBareHotItems(EntityMaid maid, MaidInventory inv, HotItemHazardCompatProvider active) {
        boolean dropped = false;
        for (int slot = 0; slot <= MaidInventoryLayout.OFF_HAND; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0) continue;
            if (active.inspectItemHeatDamage(stack) <= 0.0F) continue;
            ItemStack droppedCopy = stack.copy();
            EntityItem entityItem = new EntityItem(
                maid.worldObj,
                maid.posX,
                maid.posY + HotItemHazardTuning.DROP_HEIGHT_OFFSET,
                maid.posZ,
                droppedCopy);
            entityItem.delayBeforeCanPickup = HotItemHazardTuning.DROP_PICKUP_DELAY_TICKS;
            if (!maid.worldObj.spawnEntityInWorld(entityItem)) continue;
            if (inv.getStackInSlot(slot) != stack || !ItemStack.areItemStacksEqual(stack, droppedCopy)) {
                entityItem.setDead();
                continue;
            }
            inv.setInventorySlotContents(slot, null);
            dropped = true;
        }
        return dropped;
    }
}
