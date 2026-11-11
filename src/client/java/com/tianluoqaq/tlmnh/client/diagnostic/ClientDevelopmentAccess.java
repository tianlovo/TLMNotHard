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
        Objects.requireNonNull(implementation, ClientDiagnosticIds.ID_IMPLEMENTATION);
        if (implementation == ClientDevelopmentHooks.NONE) {
            throw new IllegalArgumentException(ClientDiagnosticDiagnostics.MSG_CANNOT_INSTALL_NONE_AS_AN);
        }
        if (hooks != ClientDevelopmentHooks.NONE && hooks != implementation) {
            throw new IllegalStateException(
                ClientDiagnosticDiagnostics.MSG_CLIENTDEVELOPMENTHOOKS_ALREADY_INSTALLED + hooks.getClass()
                    .getName());
        }
        hooks = implementation;
        active = true;
    }
}
