package com.tianluoqaq.tlmnh.common.diagnostic;

import java.util.Objects;

/** 开发与 QA 系统属性的集中读取口，仅依赖 JDK，早期类加载阶段也可安全查询 */
public final class DevelopmentFlags {

    private DevelopmentFlags() {}

    public static boolean isDevCommandsEnabled() {
        return Boolean.getBoolean(DiagnosticIds.DEV_COMMANDS);
    }

    public static void requireDevCommands() {
        requireDevCommands(DiagnosticDiagnostics.MSG_DEVELOPMENT_COMMAND_REQUIRED);
    }

    public static void requireDevCommands(String message) {
        if (!isDevCommandsEnabled()) throw new IllegalStateException(message);
    }

    /** 子开关只有在主开发开关 {@link #DEV_COMMANDS} 开启时才生效 */
    public static boolean isDevFeatureEnabled(String propertyName) {
        Objects.requireNonNull(propertyName, DiagnosticIds.ID_PROPERTYNAME);
        return isDevCommandsEnabled() && Boolean.getBoolean(propertyName);
    }

    public static boolean isQaProjectileImpactFallback() {
        return isDevFeatureEnabled(DiagnosticIds.QA_PROJECTILE_IMPACT_FALLBACK);
    }

    public static boolean isQaNaturalWorldgenHookDisabled() {
        return isDevFeatureEnabled(DiagnosticIds.QA_NATURAL_WORLDGEN_HOOK_DISABLED);
    }
}
