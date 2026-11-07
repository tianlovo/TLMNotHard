package com.tianluoqaq.tlmnh.common.entity;

/** 统一战斗身份的持久字段，不改变上游任务ID和网络序号 */
final class MaidCombatTaskSchema {

    static final String TASK = "MaidTask", EXTENSION = "TLMNH", STATE = "CombatTask";
    static final String VERSION = "Version", ORIGINAL = "OriginalId", REFERENCE = "ReferenceId";
    static final String PREFERENCE = "Preference", PENDING = "PreferencePending";
    static final int CURRENT_VERSION = 1;

    private MaidCombatTaskSchema() {}
}
