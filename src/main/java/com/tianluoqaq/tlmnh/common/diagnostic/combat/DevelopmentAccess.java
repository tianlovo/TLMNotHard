package com.tianluoqaq.tlmnh.common.diagnostic.combat;

import java.util.Objects;

import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

/** 统一管理公共观察点挂载状态；仅在显式开启开发开关且调试模组已安装钩子时视为激活 */
public final class DevelopmentAccess {

    private static volatile DevelopmentHooks hooks = DevelopmentHooks.NONE;

    private DevelopmentAccess() {}

    public static boolean isEnabled() {
        return DevelopmentFlags.isDevCommandsEnabled() && hooks != DevelopmentHooks.NONE;
    }

    public static DevelopmentHooks hooks() {
        return hooks;
    }

    public static synchronized void install(DevelopmentHooks implementation) {
        Objects.requireNonNull(implementation, CombatDiagnosticIds.ID_IMPLEMENTATION);
        if (implementation == DevelopmentHooks.NONE) {
            throw new IllegalArgumentException(CombatDiagnosticDiagnostics.MSG_CANNOT_INSTALL_NONE_AS_AN);
        }
        if (hooks != DevelopmentHooks.NONE && hooks != implementation) {
            throw new IllegalStateException(
                CombatDiagnosticDiagnostics.MSG_DEVELOPMENTHOOKS_ALREADY_INSTALLED + hooks.getClass()
                    .getName());
        }
        hooks = implementation;
    }

    static synchronized void resetForTesting() {
        hooks = DevelopmentHooks.NONE;
    }
}
