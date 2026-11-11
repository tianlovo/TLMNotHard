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
            .equals(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_TILE_ENTITY_FANCY_SKULL_RENDERER))
            throw new IllegalStateException(EtFuturumClientRenderDiagnostics.MSG_UNKNOWN_ACTIVE_SKULL_RENDERER_NATIVE);
        try {
            if (renderer.getClass() != Api.TYPE) throw new IllegalStateException(
                EtFuturumClientRenderDiagnostics.MSG_NATIVE_SKULL_RENDERER_IDENTITY_CHANGED);
            Api.RENDER.invoke(
                renderer,
                EtFuturumClientRenderLayout.ET_FUTURUM_INVOKE_SECOND_FACTOR,
                EtFuturumClientRenderLayout.ET_FUTURUM_INVOKE_THIRD_FACTOR,
                EtFuturumClientRenderLayout.ET_FUTURUM_INVOKE_SECOND_FACTOR,
                EtFuturumClientRenderLayout.INVOKE_SINGLE_UNIT,
                EtFuturumClientRenderLayout.ET_FUTURUM_INVOKE_SIXTH_FACTOR,
                type,
                profile);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                EtFuturumClientRenderDiagnostics.MSG_NATIVE_SKULL_RENDERING_ENTRY_FAILED,
                failure);
        }
    }

    private static final class Api {

        private static final Class<?> TYPE;
        private static final Method RENDER;

        static {
            try {
                TYPE = Class.forName(EtFuturumClientRenderReflectionSpec.TARGET_CLASS_TILE_ENTITY_FANCY_SKULL_RENDERER);
                RENDER = TYPE.getMethod(
                    EtFuturumClientRenderIds.ID_RENDERSKULL,
                    float.class,
                    float.class,
                    float.class,
                    int.class,
                    float.class,
                    int.class,
                    GameProfile.class);
                if (RENDER.getReturnType() != void.class) throw new IllegalStateException(
                    EtFuturumClientRenderDiagnostics.MSG_NATIVE_SKULL_RETURN_CONTRACT_CHANGED);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    EtFuturumClientRenderDiagnostics.MSG_NATIVE_SKULL_RENDERING_ENTRY_UNAVAILABLE,
                    failure);
            }
        }
    }
}
