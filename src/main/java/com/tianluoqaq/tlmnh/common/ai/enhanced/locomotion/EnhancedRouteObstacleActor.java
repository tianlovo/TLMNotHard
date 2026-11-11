package com.tianluoqaq.tlmnh.common.ai.enhanced.locomotion;

import java.util.function.BooleanSupplier;

/** 停止原路线前保存一个有界阻塞走廊；仅实际转为空闲时准入一次增强重评 */
public interface EnhancedRouteObstacleActor {

    /** 无可验证的局部阻塞时返回null；观察方必须继续检查当前actor授权与原世界 */
    BooleanSupplier observeBlockedRoute();
}
