package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原版游泳姿态与旧引擎之间的执行边界，游泳标志不等同于实体姿态 */
public interface MaidSwimmingActor {

    void configureWaterPath();

    boolean isClient();

    boolean onGround();

    boolean isSitting();

    boolean isPassenger();

    boolean isSleeping();

    void setSwimming(boolean swimming);

    void setSwimmingPose();

    void setStandingPose();
}
