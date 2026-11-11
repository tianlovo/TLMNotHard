package com.tianluoqaq.tlmnh.common.entity;

/** 女仆任务枚举：保留上游三项基础任务，新增格雷维护仓与 CropsNH 育种两项工作模式 */
public enum MaidTask {

    IDLE(MaidEntityIds.ID_TOUHOU_LITTLE_MAID, MaidEntityIds.ID_IDLE),
    ATTACK(MaidEntityIds.ID_TOUHOU_LITTLE_MAID, MaidEntityIds.ID_ATTACK),
    RANGED_ATTACK(MaidEntityIds.ID_TOUHOU_LITTLE_MAID, MaidEntityIds.ID_RANGED_ATTACK),
    MAINTENANCE_HATCH(MaidEntityIds.ID_TLMNH_NAMESPACE, MaidEntityIds.ID_MAINTENANCE_HATCH),
    CROPSNH_BREEDING(MaidEntityIds.ID_TLMNH_NAMESPACE, MaidEntityIds.ID_CROPSNH_BREEDING);

    private final String id;

    MaidTask(String namespacePrefix, String path) {
        this.id = namespacePrefix + path;
    }

    public String getId() {
        return id;
    }

    public boolean isCombatTask() {
        return this == ATTACK || this == RANGED_ATTACK;
    }

    public boolean isWorkTask() {
        return this == MAINTENANCE_HATCH || this == CROPSNH_BREEDING;
    }

    public static MaidTask fromId(String id) {
        for (var task : values()) {
            if (task.id.equals(id)) {
                return task;
            }
        }
        return IDLE;
    }
}
