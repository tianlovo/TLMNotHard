package com.tianluoqaq.tlmnh.compat.battlegear2.client.gui;

import net.minecraft.util.IIcon;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 目标平台的双手空槽提示，只读取当次材质图集，不缓存重载前的IIcon */
@SideOnly(Side.CLIENT)
public final class BattlegearSlotIcons {

    private BattlegearSlotIcons() {}

    public static IIcon hand(boolean offhand) {
        return IntegrationGuard
            .call(Side.CLIENT, ExternalFeature.CLIENT_SLOT_ICONS, () -> Api.icon(offhand ? 1 : 0), () -> null);
    }

    private static final class Api {

        static IIcon icon(int index) {
            return mods.battlegear2.Battlegear.proxy.getSlotIcon(index);
        }
    }
}
