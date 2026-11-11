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
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidInteractionTransfer;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxyProtocol;

/** 原版主人交互的原生平台适配，客户端只判断操作是否被接收 */
public final class MaidInteractions {

    private MaidInteractions() {}

    public static boolean canInteract(EntityMaid maid, EntityPlayer player) {
        if (player == null || player instanceof FakePlayer
            || !maid.isEntityAlive()
            || MaidLifecycleService.isPendingRedemption(maid)
            || !player.isEntityAlive()
            || player.worldObj != maid.worldObj
            || maid.getDistanceSqToEntity(player) > MaidInteractionTuning.INTERACTIONS_CAN_INTERACT_FACTOR)
            return false;
        // EFR 在原生平台注册的旁观模式ID为3，不对其客户端类建立公共代码依赖
        return !(player instanceof EntityPlayerMP serverPlayer) || serverPlayer.theItemInWorldManager.getGameType()
            .getID() != MaidInteractionTuning.TARGET_GET_ID_THRESHOLD;
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
                        ModContext.modInstance(),
                        CommonProxyProtocol.GUI_MAID_NAMING,
                        maid.worldObj,
                        maid.getEntityId(),
                        (int) (session >>> MaidInteractionTuning.UPPER_WORD_SHIFT),
                        (int) session);
                } else {
                    player.addChatMessage(
                        new ChatComponentTranslation(MaidInteractionIds.I18N_GUI_TLMNH_NAMING_PUT_DOWN_ITEM));
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
                if (!maid.worldObj.isRemote)
                    consume(maid, player, held, MaidInteractionTuning.CONSUME_SINGLE_UNIT, null, () -> {
                        feedGoldenApple(maid, held.getItemDamage() > 0);
                        maid.playSound(
                            MaidInteractionIds.ID_RANDOM_EAT,
                            MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_SECOND_FACTOR,
                            MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_THIRD_FACTOR);
                    });
                return true;
            }
            // 1.7.10以metadata区分喷溅药水，上游的POTION仅指可饮用药水
            if (held.getItem() == Items.potionitem && !ItemPotion.isSplash(held.getItemDamage())) {
                if (!maid.worldObj.isRemote) {
                    var effects = ((ItemPotion) held.getItem()).getEffects(held);
                    consume(
                        maid,
                        player,
                        held,
                        MaidInteractionTuning.CONSUME_SINGLE_UNIT,
                        new ItemStack(Items.glass_bottle),
                        () -> {
                            if (effects != null) {
                                for (PotionEffect effect : effects) {
                                    var potion = Potion.potionTypes[effect.getPotionID()];
                                    if (potion.isInstant()) {
                                        potion.affectEntity(
                                            player,
                                            maid,
                                            effect.getAmplifier(),
                                            MaidInteractionTuning.INTERACTIONS_AFFECT_ENTITY_FOURTH_FACTOR);
                                    } else {
                                        maid.addPotionEffect(new PotionEffect(effect));
                                    }
                                }
                            }
                            maid.playSound(
                                MaidInteractionIds.ID_RANDOM_DRINK,
                                MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_SECOND_FACTOR,
                                MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_THIRD_FACTOR);
                        });
                }
                return true;
            }
            if (held.getItem() == Items.milk_bucket) {
                if (!maid.worldObj.isRemote) consume(
                    maid,
                    player,
                    held,
                    MaidInteractionTuning.CONSUME_SINGLE_UNIT,
                    new ItemStack(Items.bucket),
                    () -> {
                        maid.curePotionEffects(held);
                        maid.playSound(
                            MaidInteractionIds.ID_RANDOM_DRINK,
                            MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_SECOND_FACTOR,
                            MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_THIRD_FACTOR);
                    });
                return true;
            }
        }
        if (held.getItem() == Items.glass_bottle
            && maid.getExperience() >= MaidInteractionTuning.EXPERIENCE_PER_BOTTLE) {
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
        maid.fallDistance = MaidInteractionTuning.INTERACTIONS_FALL_DISTANCE;
    }

    private static void feedGoldenApple(EntityMaid maid, boolean enchanted) {
        // MC1.20.1 Foods的效果，不能套用1.7.10附魔金苹果的再生V
        maid.addPotionEffect(
            new PotionEffect(
                Potion.regeneration.id,
                enchanted ? MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM
                    : MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM_2,
                MaidInteractionTuning.CONSUME_SINGLE_UNIT));
        maid.addPotionEffect(
            new PotionEffect(
                Potion.field_76444_x.id,
                MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM_3,
                enchanted ? MaidInteractionTuning.TARGET_GET_ID_THRESHOLD
                    : MaidInteractionTuning.POTION_EFFECT_INITIAL_VALUE));
        if (enchanted) {
            maid.addPotionEffect(
                new PotionEffect(
                    Potion.resistance.id,
                    MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM_4,
                    MaidInteractionTuning.POTION_EFFECT_INITIAL_VALUE));
            maid.addPotionEffect(
                new PotionEffect(
                    Potion.fireResistance.id,
                    MaidInteractionTuning.INTERACTIONS_POTION_EFFECT_SECOND_PARAM_4,
                    MaidInteractionTuning.POTION_EFFECT_INITIAL_VALUE));
        }
    }

    private static void exchangeExperience(EntityMaid maid, EntityPlayer player, ItemStack held) {
        int count = player.isSneaking()
            ? Math.min(held.stackSize, maid.getExperience() / MaidInteractionTuning.EXPERIENCE_PER_BOTTLE)
            : MaidInteractionTuning.CONSUME_SINGLE_UNIT;
        count = Math.min(count, Items.experience_bottle.getItemStackLimit());
        if (MaidInteractionTransfer.publish(
            maid,
            player,
            held,
            count,
            new ItemStack(Items.experience_bottle, count),
            count * MaidInteractionTuning.EXPERIENCE_PER_BOTTLE,
            () -> maid.playSound(
                MaidInteractionIds.ID_RANDOM_POP,
                MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_SECOND_FACTOR_2,
                MaidInteractionTuning.INTERACTIONS_PLAY_SOUND_THIRD_FACTOR_2)))
            player.inventoryContainer.detectAndSendChanges();
    }

    private static boolean consume(EntityMaid maid, EntityPlayer player, ItemStack held, int count, ItemStack container,
        Runnable effect) {
        if (player.capabilities.isCreativeMode) {
            effect.run();
            return true;
        }
        int remaining = held.stackSize - count;
        if (remaining > 0 && container != null && !player.inventory.addItemStackToInventory(container)) {
            boolean delivered = MaidInteractionTransfer.publish(
                maid,
                player,
                held,
                count,
                container,
                MaidInteractionTuning.POTION_EFFECT_INITIAL_VALUE,
                effect);
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
