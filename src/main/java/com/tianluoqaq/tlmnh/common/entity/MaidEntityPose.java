package com.tianluoqaq.tlmnh.common.entity;

/** 对应现代实体 Pose，与游泳/睡眠事实分别同步 */
public enum MaidEntityPose {

    STANDING(MaidEntityTuning.ENTITY_POSE_STANDING_FIRST_FACTOR, MaidEntityTuning.ENTITY_POSE_STANDING_SECOND_FACTOR),
    SWIMMING(MaidEntityTuning.ENTITY_POSE_STANDING_FIRST_FACTOR, MaidEntityTuning.ENTITY_POSE_STANDING_FIRST_FACTOR),
    SLEEPING(MaidEntityTuning.ENTITY_POSE_SLEEPING_FIRST_FACTOR, MaidEntityTuning.ENTITY_POSE_SLEEPING_FIRST_FACTOR);

    public final float width, height;

    MaidEntityPose(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public static MaidEntityPose decode(byte value) {
        return value >= 0 && value < values().length ? values()[value] : STANDING;
    }
}
