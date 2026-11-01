package com.tianluoqaq.tlmnh.client.render;

import java.util.function.Supplier;

import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.angelica.client.render.MaidRenderPlatformState;

import cpw.mods.fml.relauncher.Side;

/** 在拥有任何GL写入前检查窄接口；失败仅跳过本机女仆展示 */
public final class MaidRenderProtection {

    private MaidRenderProtection() {}

    public static boolean ready() {
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, () -> {
            MaidRenderPlatformState.preflight();
            return true;
        }, () -> false);
    }

    public static void draw(Runnable draw) {
        call(() -> {
            draw.run();
            return true;
        }, () -> false);
    }

    public static <T> T call(IntegrationGuard.Call<T> operation, Supplier<T> fallback) {
        if (!ready()) return fallback.get();
        return IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, operation, fallback);
    }

    public static void failed(Throwable failure) {
        IntegrationGuard.trip(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, failure);
    }
}
