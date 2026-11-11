package com.tianluoqaq.tlmnh.api.behavior;

/**
 * 外部行为模块可提交提案的优先级分组。
 * 紧急生存（EMERGENCY）与主人硬命令（COMMAND）由平台内部独占，不在公开枚举中暴露，从类型层面阻断越权提案
 */
public enum MaidBehaviorGroup {
    DANGER,
    RECOVERY,
    COMBAT,
    CARE,
    AUTONOMOUS,
    AMBIENT
}
