package com.tianluoqaq.tlmnh.common.entity;

/** 由服务端行为设置的短时动作，不写入存档，不代替任务和作息 */
public enum MaidPose {

    SLEEPING(MaidEntityTuning.SLEEPING_SINGLE_UNIT),
    BEGGING(MaidEntityTuning.POSE_BEGGING_FIRST_PARAM),
    USING_ITEM(MaidEntityTuning.POSE_USING_ITEM_FIRST_PARAM),
    USING_OFFHAND(MaidEntityTuning.POSE_USING_OFFHAND_FIRST_PARAM),
    SWING_OFFHAND(MaidEntityTuning.POSE_SWING_OFFHAND_FIRST_PARAM),
    AIMING_BOW(MaidEntityTuning.POSE_AIMING_BOW_FIRST_PARAM),
    SWIMMING(MaidEntityTuning.POSE_SWIMMING_FIRST_PARAM),
    SWINGING_ARMS(MaidEntityTuning.POSE_SWINGING_ARMS_FIRST_PARAM);

    private final int mask;

    MaidPose(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }
}
