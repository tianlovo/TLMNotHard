package com.tianluoqaq.tlmnh.common.ai.navigation;

import net.minecraft.pathfinding.PathEntity;

/** 模式切换只延续旧动作的取消凭据，不为新动作授予执行权 */
public final class MaidPathOwnership {

    private MaidPathOwnership() {}

    public static boolean owns(PathEntity current, PathEntity owned) {
        return owned != null
            && (current == owned || current instanceof MaidModernPath modern && modern.continues(owned));
    }
}
