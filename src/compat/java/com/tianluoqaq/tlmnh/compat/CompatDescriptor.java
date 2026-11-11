package com.tianluoqaq.tlmnh.compat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 单个外部模组适配的元数据契约，声明真实 Mod ID、接入阶段、运行端与已核版本 */
public interface CompatDescriptor {

    enum Phase {
        MIXIN,
        PRE_INIT,
        INIT,
        POST_INIT,
        RUNTIME
    }

    enum SideScope {
        COMMON,
        CLIENT,
        BOTH
    }

    String modId();

    String packageSegment();

    String reviewedVersion();

    SideScope sideScope();

    Phase primaryPhase();

    default List<String> requiredPeerModIds() {
        return Collections.emptyList();
    }

    String capabilitySummary();

    static String requireNonEmpty(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim()
            .isEmpty()) throw new IllegalArgumentException(name + CompatBootstrapDiagnostics.MSG_MUST_NOT_BE_EMPTY);
        return value;
    }
}
