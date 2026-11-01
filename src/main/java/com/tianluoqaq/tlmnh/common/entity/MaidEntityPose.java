package com.tianluoqaq.tlmnh.common.entity;

/** 对应现代实体 Pose，与游泳/睡眠事实分别同步 */
public enum MaidEntityPose {

    STANDING(.6F, 1.5F),
    SWIMMING(.6F, .6F),
    SLEEPING(.2F, .2F);

    public final float width, height;

    MaidEntityPose(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public static MaidEntityPose decode(byte value) {
        return value >= 0 && value < values().length ? values()[value] : STANDING;
    }
}
