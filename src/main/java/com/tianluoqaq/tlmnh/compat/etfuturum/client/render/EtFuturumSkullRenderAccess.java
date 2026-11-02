package com.tianluoqaq.tlmnh.compat.etfuturum.client.render;

import java.lang.reflect.Method;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 目标EFR实际注册头颅渲染器的原生入口，保留其皮肤与外层模型；调用方持有显示保护边界 */
@SideOnly(Side.CLIENT)
public final class EtFuturumSkullRenderAccess {

    private EtFuturumSkullRenderAccess() {}

    public static void render(TileEntitySpecialRenderer renderer, int type, GameProfile profile) {
        if (renderer == null || !renderer.getClass()
            .getName()
            .equals("ganymedes01.etfuturum.client.renderer.tileentity.TileEntityFancySkullRenderer"))
            throw new IllegalStateException("Unknown active skull renderer; native head item retained");
        try {
            if (renderer.getClass() != Api.TYPE)
                throw new IllegalStateException("Native skull renderer identity changed");
            Api.RENDER.invoke(renderer, -.5F, 0F, -.5F, 1, 180F, type, profile);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native skull rendering entry failed", failure);
        }
    }

    private static final class Api {

        private static final Class<?> TYPE;
        private static final Method RENDER;

        static {
            try {
                TYPE = Class.forName("ganymedes01.etfuturum.client.renderer.tileentity.TileEntityFancySkullRenderer");
                RENDER = TYPE.getMethod(
                    "renderSkull",
                    float.class,
                    float.class,
                    float.class,
                    int.class,
                    float.class,
                    int.class,
                    GameProfile.class);
                if (RENDER.getReturnType() != void.class)
                    throw new IllegalStateException("Native skull return contract changed");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native skull rendering entry unavailable", failure);
            }
        }
    }
}
