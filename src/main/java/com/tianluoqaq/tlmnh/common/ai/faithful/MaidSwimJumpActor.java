package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原版 Swim 的流体输入和两种独立 RNG，不直接读写世界 */
public interface MaidSwimJumpActor {

    boolean inWater();

    double waterHeight();

    double fluidJumpThreshold();

    boolean inLava();

    boolean underWater();

    boolean navigationDone();

    int nextWorldInt(int bound);

    float nextEntityFloat();

    void requestJump();
}
