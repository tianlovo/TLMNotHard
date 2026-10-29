package com.tianluoqaq.tlmnh.common.entity;

/** 首版保留的上游任务 ID，行为实现由单一服务端协调器负责 */
public enum MaidTask {

    IDLE("idle"),
    ATTACK("attack"),
    RANGED_ATTACK("ranged_attack");

    private final String id;

    MaidTask(String path) {
        this.id = "touhou_little_maid:" + path;
    }

    public String getId() {
        return id;
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
