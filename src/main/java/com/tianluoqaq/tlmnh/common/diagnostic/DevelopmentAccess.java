package com.tianluoqaq.tlmnh.common.diagnostic;

import java.util.Objects;

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
        Objects.requireNonNull(implementation, "implementation");
        if (implementation == DevelopmentHooks.NONE) {
            throw new IllegalArgumentException("Cannot install NONE as an active development hook");
        }
        if (hooks != DevelopmentHooks.NONE && hooks != implementation) {
            throw new IllegalStateException(
                "DevelopmentHooks already installed: " + hooks.getClass()
                    .getName());
        }
        hooks = implementation;
    }

    static synchronized void resetForTesting() {
        hooks = DevelopmentHooks.NONE;
    }
}
