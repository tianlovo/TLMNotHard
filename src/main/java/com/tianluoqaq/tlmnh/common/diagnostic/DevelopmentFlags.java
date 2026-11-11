package com.tianluoqaq.tlmnh.common.diagnostic;

import java.util.Objects;

/** 开发与 QA 系统属性的集中读取口，仅依赖 JDK，早期类加载阶段也可安全查询 */
public final class DevelopmentFlags {

    public static final String DEV_COMMANDS = "tlmnh.devCommands";
    public static final String QA_PROJECTILE_IMPACT_FALLBACK = "tlmnh.qaProjectileImpactFallback";
    public static final String QA_NATURAL_WORLDGEN_HOOK_DISABLED = "tlmnh.qaNaturalWorldgenHookDisabled";

    private DevelopmentFlags() {}

    public static boolean isDevCommandsEnabled() {
        return Boolean.getBoolean(DEV_COMMANDS);
    }

    public static void requireDevCommands() {
        requireDevCommands("Development command required");
    }

    public static void requireDevCommands(String message) {
        if (!isDevCommandsEnabled()) throw new IllegalStateException(message);
    }

    /** 子开关只有在主开发开关 {@link #DEV_COMMANDS} 开启时才生效 */
    public static boolean isDevFeatureEnabled(String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        return isDevCommandsEnabled() && Boolean.getBoolean(propertyName);
    }

    public static boolean isQaProjectileImpactFallback() {
        return isDevFeatureEnabled(QA_PROJECTILE_IMPACT_FALLBACK);
    }

    public static boolean isQaNaturalWorldgenHookDisabled() {
        return isDevFeatureEnabled(QA_NATURAL_WORLDGEN_HOOK_DISABLED);
    }
}
