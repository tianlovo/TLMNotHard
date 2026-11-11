package com.tianluoqaq.tlmnh.common.ai.faithful.follow;

import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.BlockPos;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.Path;

/** 单个移动sink的运行状态，不持有原生世界，也不保存到实体NBT */
public final class FollowMoveState {

    public enum Status {
        STOPPED,
        RUNNING
    }

    public Status status = Status.STOPPED;
    public int remainingCooldown;
    public Path path;
    public BlockPos lastTargetPos;
    public float speedModifier;
    public long endTimestamp;

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
