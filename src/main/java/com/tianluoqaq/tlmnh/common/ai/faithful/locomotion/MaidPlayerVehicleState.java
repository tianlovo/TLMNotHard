package com.tianluoqaq.tlmnh.common.ai.faithful.locomotion;

/** 原PlayerMixin的3000毫秒离船窗口，使用单调时间而非世界tick */
public interface MaidPlayerVehicleState {

    boolean tlmnh$inDismountCooldown();
}
