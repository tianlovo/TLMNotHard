package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveActor.Path;

/** 单个移动sink的运行状态，不持有原生世界，也不保存到实体NBT */
public final class FollowMoveState {

    public enum Status {
        STOPPED,
        RUNNING
    }

    Status status = Status.STOPPED;
    int remainingCooldown;
    Path path;
    BlockPos lastTargetPos;
    float speedModifier;
    long endTimestamp;

    public Status getStatus() {
        return status;
    }

    public int getRemainingCooldown() {
        return remainingCooldown;
    }

    public Path getPath() {
        return path;
    }

    public BlockPos getLastTargetPos() {
        return lastTargetPos;
    }

    public float getSpeedModifier() {
        return speedModifier;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }
}
