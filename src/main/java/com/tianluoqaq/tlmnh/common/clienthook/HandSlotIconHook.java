package com.tianluoqaq.tlmnh.common.clienthook;

import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 主副手槽位背景图标客户端钩子；默认返回空图标，由 compatClient 在客户端启动时安装实现 */
@SideOnly(Side.CLIENT)
public final class HandSlotIconHook {

    @FunctionalInterface
    public interface Provider {

        IIcon iconForHand(boolean offhand);
    }

    private static volatile Provider provider = offhand -> null;

    private HandSlotIconHook() {}

    public static void install(Provider next) {
        provider = next != null ? next : (offhand -> null);
    }

    public static IIcon iconForHand(boolean offhand) {
        return provider.iconForHand(offhand);
    }
}
