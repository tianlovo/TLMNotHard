package com.tianluoqaq.tlmnh.api.behavior;

/** 单帧提案求值阶段，决定各行为模块在竞标循环中的确定性顺序槽位 */
public enum MaidBehaviorStage {

    /** 呼吸自救与恐慌脱困 */
    SURVIVAL_AND_DANGER,

    /** 乘骑同步、待命姿态、返家与跟随主人 */
    COMMAND_AND_POSTURE,

    /** 统一战斗、自疗进食与扩展战斗策略 */
    COMBAT_AND_RECOVERY,

    /** 工作餐、睡眠、零食柜交互、拾取与扩展日常活动 */
    CARE_AND_AUTONOMOUS,

    /** 乞食、雪仗、观察与漫步；仅当高层阶段无有效提案时求值 */
    AMBIENT_IDLE
}
