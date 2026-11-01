package com.tianluoqaq.tlmnh.common.inventory;

import java.util.ArrayList;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 上游接触拾取的 native 适配，世界实体和库存只在服务端交易 */
public final class MaidPickupService {

    private final EntityMaid maid;

    public MaidPickupService(EntityMaid maid) {
        this.maid = maid;
    }

    public void tick() {
        if (!available()) return;
        for (Object value : maid.worldObj
            .getEntitiesWithinAABBExcludingEntity(maid, maid.boundingBox.expand(0.5, 0.5, 0.5))) {
            if (value instanceof EntityItem item && maid.canPickupItems()) pickupItem(item, false);
            else if (value instanceof EntityXPOrb orb && maid.canPickupExperience()) pickupExperience(orb);
            else if (value instanceof EntityArrow arrow && maid.canPickupItems()) pickupArrow(arrow, false);
        }
    }

    private boolean available() {
        return !maid.worldObj.isRemote && maid.isEntityAlive() && maid.isTamed() && maid.isPickup();
    }

    private boolean valid(Entity entity) {
        return available() && entity != null && entity.worldObj == maid.worldObj && !entity.isDead;
    }

    public boolean canPickup(Entity entity, boolean rejectWater) {
        if (!valid(entity) || rejectWater && entity.isInWater()) return false;
        if (entity instanceof EntityItem item) return pickupItem(item, true);
        if (entity instanceof EntityArrow arrow) return pickupArrow(arrow, true);
        return entity instanceof EntityXPOrb orb && maid.canPickupExperience() && orb.xpValue > 0;
    }

    public boolean pickupItem(EntityItem item, boolean simulate) {
        if (!valid(item) || !maid.canPickupItems() || item.delayBeforeCanPickup > 0) return false;
        var event = new MaidPickupEvent.ItemResultPre(maid, item, simulate);
        if (MinecraftForge.EVENT_BUS.post(event)) return event.isCanPickup();
        if (!valid(item)) return false;
        var input = item.getEntityItem();
        if (input == null || input.stackSize <= 0) return false;
        var remaining = maid.getMaidInventory()
            .insertAvailable(input, simulate);
        int count = input.stackSize - (remaining == null ? 0 : remaining.stackSize);
        if (count <= 0) return false;
        if (!simulate) {
            var taken = input.copy();
            taken.stackSize = count;
            maid.onItemPickup(item, count);
            if (remaining == null) item.setDead();
            else item.setEntityItemStack(remaining);
            maid.tryPlayMaidPickupSound();
            MinecraftForge.EVENT_BUS.post(new MaidPickupEvent.ItemResultPost(maid, taken));
        }
        return true;
    }

    public void pickupExperience(EntityXPOrb orb) {
        if (!valid(orb) || !maid.canPickupExperience() || orb.ticksExisted <= 2 || orb.xpValue <= 0) return;
        if (MinecraftForge.EVENT_BUS.post(new MaidPickupEvent.ExperienceResult(maid, orb, false)) || !valid(orb))
            return;
        int original = orb.xpValue;
        int remaining = repairWithMending(original);
        int added = (int) Math.min((long) Integer.MAX_VALUE - maid.getExperience(), remaining);
        if (added > 0) maid.setExperience(maid.getExperience() + added);
        if (remaining != original || added > 0) {
            maid.onItemPickup(orb, 1);
            maid.tryPlayMaidPickupSound();
        }
        orb.xpValue = remaining - added;
        if (orb.xpValue == 0) orb.setDead();
    }

    /** native Et Futurum 注册名为 enchantment.mending；保留 TLM 随机选择一件装备的规则 */
    private int repairWithMending(int xp) {
        Enchantment mending = null;
        for (var enchantment : Enchantment.enchantmentsList)
            if (enchantment != null && "enchantment.mending".equals(enchantment.getName())) {
                mending = enchantment;
                break;
            }
        if (mending == null) return xp;
        var candidates = new ArrayList<ItemStack>();
        var inventory = maid.getMaidInventory();
        for (int slot = MaidInventory.MAIN_HAND; slot < MaidInventory.TOTAL_SIZE; slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.isItemDamaged()
                && stack.getItem()
                    .isRepairable()
                && EnchantmentHelper.getEnchantmentLevel(mending.effectId, stack) > 0) candidates.add(stack);
        }
        if (candidates.isEmpty()) return xp;
        var selected = candidates.get(
            maid.getRNG()
                .nextInt(candidates.size()));
        int repaired = (int) Math.min((long) xp * 2, selected.getItemDamage());
        selected.setItemDamage(selected.getItemDamage() - repaired);
        inventory.markDirty();
        return xp - repaired / 2;
    }

    public boolean pickupArrow(EntityArrow arrow, boolean simulate) {
        // 自定义箭可能对应另一种物品；首版只回收 native 原生箭，不将未知箭转换为普通箭
        if (!valid(arrow) || !maid.canPickupItems()
            || arrow.getClass() != EntityArrow.class
            || arrow.arrowShake > 0
            || arrow.canBePickedUp != 1) return false;
        var state = new NBTTagCompound();
        arrow.writeToNBT(state);
        if (!state.getBoolean("inGround")) return false;
        var event = new MaidPickupEvent.ArrowResult(maid, arrow, simulate);
        if (MinecraftForge.EVENT_BUS.post(event)) return event.isCanPickup();
        if (!valid(arrow) || maid.getMaidInventory()
            .insertAvailable(new ItemStack(Items.arrow), true) != null) return false;
        if (!simulate) {
            maid.getMaidInventory()
                .insertAvailable(new ItemStack(Items.arrow), false);
            maid.onItemPickup(arrow, 1);
            arrow.setDead();
            maid.tryPlayMaidPickupSound();
        }
        return true;
    }
}
