package com.tianluoqaq.tlmnh.compat.backhand.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.OptionalApi;
import com.tianluoqaq.tlmnh.compat.backhand.BackhandCompat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只查询真实副手槽，保留当前活动手与物品原值 */
@SideOnly(Side.CLIENT)
public final class BackhandPlayerItems {

    private BackhandPlayerItems() {}

    public static ItemStack offhand(EntityPlayer player) {
        if (player == null || !OptionalApi.isModLoaded(BackhandCompat.MOD_ID)
            || !IntegrationGuard.available(Side.CLIENT, BackhandClientIds.PLAYER_ITEMS_GUARD)) return null;
        try {
            if (Api.OFFHAND_SLOT == null) return null;
            int slot = (int) Api.OFFHAND_SLOT.invokeExact(player);
            return slot < 0 ? null : player.inventory.getStackInSlot(slot);
        } catch (Throwable failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.CLIENT, BackhandClientIds.PLAYER_ITEMS_GUARD, failure);
            return null;
        }
    }

    private static final class Api {

        private static final MethodHandle OFFHAND_SLOT = OptionalApi.findStatic(
            BackhandCompat.MOD_ID,
            BackhandClientReflectionSpec.UTILS_CLASS,
            BackhandClientReflectionSpec.OFFHAND_SLOT,
            MethodType.methodType(int.class, EntityPlayer.class));
    }
}
