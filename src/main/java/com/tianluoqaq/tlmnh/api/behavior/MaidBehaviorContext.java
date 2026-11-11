package com.tianluoqaq.tlmnh.api.behavior;

/** 单女仆行为会话上下文；动作与手部访问仅在当前获胜会话的 execute 阶段开放 */
public interface MaidBehaviorContext {

    MaidObservationView observation();

    /** 仅在活跃 execute 回调内可用；在 offer、切换回调或过期引用上调用均抛出 IllegalStateException */
    MaidActionAccess actions();

    /** 仅在活跃 execute 回调内可用；在 offer、切换回调或过期引用上调用均抛出 IllegalStateException */
    MaidHandAccess hands();
}
