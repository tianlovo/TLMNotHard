/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidSwimJumpTask.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: actor boundary includes vanilla Swim and Behavior lifecycle inputs
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSwimJumpActor;

public final class MaidSwimJumpTask {

    private final float chance;
    private boolean running;
    private long endTimestamp;

    public MaidSwimJumpTask(float chance) {
        this.chance = chance;
    }

    /** Brain 的启动 pass 后运行 pass，不在水下或仍有导航时发出被动跳跃 */
    public void tick(MaidSwimJumpActor maid, long gameTime) {
        tryStart(maid, gameTime);
        tickOrStop(maid, gameTime);
    }

    public void tryStart(MaidSwimJumpActor maid, long gameTime) {
        if (!running && fluidCondition(maid)) {
            running = true;
            endTimestamp = gameTime + 60 + maid.nextWorldInt(1);
        }
    }

    public void tickOrStop(MaidSwimJumpActor maid, long gameTime) {
        if (!running) return;
        if (gameTime > endTimestamp || !fluidCondition(maid) || maid.underWater() || !maid.navigationDone()) {
            running = false;
            return;
        }
        if (maid.nextEntityFloat() < chance) maid.requestJump();
    }

    private boolean fluidCondition(MaidSwimJumpActor maid) {
        return maid.inWater() && maid.waterHeight() > maid.fluidJumpThreshold() || maid.inLava();
    }

    public void cancel() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }
}
