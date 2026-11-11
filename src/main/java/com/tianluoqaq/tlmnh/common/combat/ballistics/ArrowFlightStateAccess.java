package com.tianluoqaq.tlmnh.common.combat.ballistics;

/** 只读暴露箭矢离地飞行帧计数，使弹道实体无需直接导入 mixin 包 */
public interface ArrowFlightStateAccess {

    int tlmnh$getAirTicks();
}
