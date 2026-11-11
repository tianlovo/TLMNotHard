package com.tianluoqaq.tlmnh.api.behavior;

/** 活跃行为会话切出原因，供模块区分短期抢占与生命周期级彻底清理 */
public enum TransitionReason {

    /** 本模块不再提交提案或主动完成，自然退回空闲与低层活动 */
    COMPLETED_OR_IDLE,

    /** 被更高优先级分组或同组更高分提案接管，模块可按自身边界保留短期观测 */
    PREEMPTED,

    /** 骑乘或待命坐下姿态发生改变 */
    POSTURE_CHANGED,

    /** 任务切换、跨维、不可移动门控、策略重置或实体卸载，须释放全部运行资源 */
    BRAIN_INVALIDATED,

    /** 外部扩展模块抛出未捕获异常被平台熔断隔离 */
    MODULE_FAULTED
}
