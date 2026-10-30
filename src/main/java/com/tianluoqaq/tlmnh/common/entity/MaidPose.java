package com.tianluoqaq.tlmnh.common.entity;

/** 由服务端行为设置的短时动作，不写入存档，不代替任务和作息 */
public enum MaidPose {

    SLEEPING(1),
    BEGGING(2),
    USING_ITEM(4),
    USING_OFFHAND(8),
    SWING_OFFHAND(16),
    AIMING_BOW(32),
    SWIMMING(64);

    private final int mask;

    MaidPose(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }
}
