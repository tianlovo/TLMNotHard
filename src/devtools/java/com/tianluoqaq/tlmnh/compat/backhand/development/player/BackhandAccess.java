package com.tianluoqaq.tlmnh.compat.backhand.development.player;

import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayer;

/** 开发夹具通过实装API选择真实副手槽，生产交互继续使用原生持物入口 */
public final class BackhandAccess {

    private BackhandAccess() {}

    public static int offhandSlot(EntityPlayer player) throws ReflectiveOperationException {
        return (Integer) Class.forName("xonin.backhand.api.core.BackhandUtils")
            .getMethod("getOffhandSlot", EntityPlayer.class)
            .invoke(null, player);
    }

    public static boolean usingOffhand(EntityPlayer player, BooleanSupplier action)
        throws ReflectiveOperationException {
        return (Boolean) Class.forName("xonin.backhand.api.core.BackhandUtils")
            .getMethod("useOffhandItem", EntityPlayer.class, boolean.class, BooleanSupplier.class)
            .invoke(null, player, false, action);
    }
}
