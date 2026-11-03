/*
 * Copyright (c) 2019-2025 tartaric_acid, for the code part
 * Adapted from TouhouLittleMaid, commit 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * event/maid/{SaddleMaidEvent,ApplyGoldenAppleEvent,ApplyPotionEffectEvent,GetExpBottleEvent,UseNameTagEvent}.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: server authority, native hands, native 1.7.10 riding and item conservation
 */
package com.tianluoqaq.tlmnh.common.interaction;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

/** 原版主人交互的原生平台适配，客户端只判断操作是否被接收 */
public final class MaidInteractions {

    public static final int EXPERIENCE_PER_BOTTLE = 12;

    private MaidInteractions() {}

    public static boolean canInteract(EntityMaid maid, EntityPlayer player) {
        if (player == null || player instanceof FakePlayer
            || !maid.isEntityAlive()
            || com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService.isPendingRedemption(maid)
            || !player.isEntityAlive()
            || player.worldObj != maid.worldObj
            || maid.getDistanceSqToEntity(player) > 64.0D) return false;
        // EFR 在原生平台注册的旁观模式ID为3，不对其客户端类建立公共代码依赖
        return !(player instanceof EntityPlayerMP serverPlayer) || serverPlayer.theItemInWorldManager.getGameType()
            .getID() != 3;
    }

    public static boolean isMainHand(EntityPlayer player) {
        int slot = player.inventory.currentItem;
        return slot >= 0 && slot < InventoryPlayer.getHotbarSize();
    }

    public static boolean interactOwned(EntityMaid maid, EntityPlayer player, ItemStack held) {
        if (!canInteract(maid, player) || !maid.isOwner(player)
            || !isMainHand(player)
            || held == null
            || held.stackSize <= 0) return false;
        if (held.getItem() == Items.name_tag) {
            if (!maid.worldObj.isRemote) {
                if (held.hasDisplayName()) {
                    var used = player.capabilities.isCreativeMode ? held.copy() : held;
                    used.interactWithEntity(player, maid);
                    if (used.stackSize <= 0 && !player.capabilities.isCreativeMode)
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                } else if (player.inventory.getItemStack() == null) {
                    long session;
                    do session = ThreadLocalRandom.current()
                        .nextLong();
                    while (session == 0);
                    player.openGui(
                        TLMNotHard.INSTANCE,
                        CommonProxy.GUI_MAID_NAMING,
                        maid.worldObj,
                        maid.getEntityId(),
                        (int) (session >>> 32),
                        (int) session);
                } else {
                    player.addChatMessage(new ChatComponentTranslation("gui.tlmnh.naming.put_down_item"));
                }
            }
            return true;
        }
        if (held.getItem() == Items.saddle) {
            if (!maid.worldObj.isRemote) toggleCarry(maid, player);
            return true;
        }
        if (player.isSneaking()) {
            if (held.getItem() == Items.golden_apple) {
                if (!maid.worldObj.isRemote) consume(maid, player, held, 1, null, () -> {
                    feedGoldenApple(maid, held.getItemDamage() > 0);
                    maid.playSound("random.eat", 0.6F, 1.0F);
                });
                return true;
            }
            // 1.7.10以metadata区分喷溅药水，上游的POTION仅指可饮用药水
            if (held.getItem() == Items.potionitem && !ItemPotion.isSplash(held.getItemDamage())) {
                if (!maid.worldObj.isRemote) {
                    var effects = ((ItemPotion) held.getItem()).getEffects(held);
                    consume(maid, player, held, 1, new ItemStack(Items.glass_bottle), () -> {
                        if (effects != null) {
                            for (PotionEffect effect : effects) {
                                var potion = Potion.potionTypes[effect.getPotionID()];
                                if (potion.isInstant()) {
                                    potion.affectEntity(player, maid, effect.getAmplifier(), 1.0D);
                                } else {
                                    maid.addPotionEffect(new PotionEffect(effect));
                                }
                            }
                        }
                        maid.playSound("random.drink", 0.6F, 1.0F);
                    });
                }
                return true;
            }
            if (held.getItem() == Items.milk_bucket) {
                if (!maid.worldObj.isRemote) consume(maid, player, held, 1, new ItemStack(Items.bucket), () -> {
                    maid.curePotionEffects(held);
                    maid.playSound("random.drink", 0.6F, 1.0F);
                });
                return true;
            }
        }
        if (held.getItem() == Items.glass_bottle && maid.getExperience() >= EXPERIENCE_PER_BOTTLE) {
            if (!maid.worldObj.isRemote) exchangeExperience(maid, player, held);
            return true;
        }
        return false;
    }

    private static void toggleCarry(EntityMaid maid, EntityPlayer player) {
        if (maid.ridingEntity == player && player.riddenByEntity == maid) {
            maid.mountEntity(null);
        } else {
            // 不挤掉玩家原乘客，也不从其他坐骑上强行取走女仆
            if (maid.ridingEntity != null || maid.riddenByEntity != null || player.riddenByEntity != null) return;
            for (var mount = player.ridingEntity; mount != null; mount = mount.ridingEntity) {
                if (mount == maid) return;
            }
            maid.mountEntity(player);
            maid.setSitting(false);
            maid.setPose(MaidPose.SLEEPING, false);
        }
        maid.getNavigator()
            .clearPathEntity();
        maid.setAttackTarget(null);
        maid.fallDistance = 0.0F;
    }

    private static void feedGoldenApple(EntityMaid maid, boolean enchanted) {
        // MC1.20.1 Foods的效果，不能套用1.7.10附魔金苹果的再生V
        maid.addPotionEffect(new PotionEffect(Potion.regeneration.id, enchanted ? 400 : 100, 1));
        maid.addPotionEffect(new PotionEffect(Potion.field_76444_x.id, 2400, enchanted ? 3 : 0));
        if (enchanted) {
            maid.addPotionEffect(new PotionEffect(Potion.resistance.id, 6000, 0));
            maid.addPotionEffect(new PotionEffect(Potion.fireResistance.id, 6000, 0));
        }
    }

    private static void exchangeExperience(EntityMaid maid, EntityPlayer player, ItemStack held) {
        int count = player.isSneaking() ? Math.min(held.stackSize, maid.getExperience() / EXPERIENCE_PER_BOTTLE) : 1;
        count = Math.min(count, Items.experience_bottle.getItemStackLimit());
        if (com.tianluoqaq.tlmnh.common.lifecycle.MaidInteractionTransfer.publish(
            maid,
            player,
            held,
            count,
            new ItemStack(Items.experience_bottle, count),
            count * EXPERIENCE_PER_BOTTLE,
            () -> maid.playSound("random.pop", 0.2F, 2.0F))) player.inventoryContainer.detectAndSendChanges();
    }

    private static boolean consume(EntityMaid maid, EntityPlayer player, ItemStack held, int count, ItemStack container,
        Runnable effect) {
        if (player.capabilities.isCreativeMode) {
            effect.run();
            return true;
        }
        int remaining = held.stackSize - count;
        if (remaining > 0 && container != null && !player.inventory.addItemStackToInventory(container)) {
            boolean delivered = com.tianluoqaq.tlmnh.common.lifecycle.MaidInteractionTransfer
                .publish(maid, player, held, count, container, 0, effect);
            if (delivered) player.inventoryContainer.detectAndSendChanges();
            return delivered;
        }
        ItemStack replacement = remaining > 0 ? held.copy() : container;
        if (remaining > 0) replacement.stackSize = remaining;
        // 替换实际当前槽，不改旧栈引用；创造保留已在本方法入口处理
        player.inventory.setInventorySlotContents(player.inventory.currentItem, replacement);
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        effect.run();
        return true;
    }
}
