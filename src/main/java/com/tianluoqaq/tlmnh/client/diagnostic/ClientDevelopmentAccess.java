package com.tianluoqaq.tlmnh.client.diagnostic;

import java.util.Objects;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端观察点挂载入口；未安装调试模组时布尔快路径恒为假，避免逐顶点渲染产生接口分派 */
@SideOnly(Side.CLIENT)
public final class ClientDevelopmentAccess {

    private static volatile ClientDevelopmentHooks hooks = ClientDevelopmentHooks.NONE;
    private static volatile boolean active;

    private ClientDevelopmentAccess() {}

    public static boolean isActive() {
        return active;
    }

    public static ClientDevelopmentHooks hooks() {
        return hooks;
    }

    public static synchronized void install(ClientDevelopmentHooks implementation) {
        Objects.requireNonNull(implementation, "implementation");
        if (implementation == ClientDevelopmentHooks.NONE) {
            throw new IllegalArgumentException("Cannot install NONE as an active client development hook");
        }
        if (hooks != ClientDevelopmentHooks.NONE && hooks != implementation) {
            throw new IllegalStateException(
                "ClientDevelopmentHooks already installed: " + hooks.getClass()
                    .getName());
        }
        hooks = implementation;
        active = true;
    }
}
