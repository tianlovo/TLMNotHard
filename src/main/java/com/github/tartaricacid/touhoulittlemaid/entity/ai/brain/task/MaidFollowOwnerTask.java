/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidFollowOwnerTask.java
 * Copyright (c) 2019-2025 tartaric_acid, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure follow decision core; modern Behavior and native effects use FollowOwnerActor
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowOwnerActor.Owner;

/** 原版 FollowOwner 的检查与启动逻辑，不包含移动 sink、日程或完整 F 策略 */
public final class MaidFollowOwnerTask {

    private final float speedModifier;
    private final int stopDistance;

    public MaidFollowOwnerTask(float speedModifier, int stopDistance) {
        this.speedModifier = speedModifier;
        this.stopDistance = stopDistance;
    }

    /**
     * 仅表示本次条件检查通过并调用 start，不表示产生路径或传送成功
     * 调用者负责 WALK_TARGET 已注册的前提、运行 token 和外层 Behavior 调度
     * 此方法不模拟原版 Behavior 的运行状态、持续时间或世界随机数消耗
     */
    public boolean tryStart(FollowOwnerActor maid) {
        if (!checkExtraStartConditions(maid)) {
            return false;
        }
        start(maid);
        return true;
    }

    public boolean checkExtraStartConditions(FollowOwnerActor maid) {
        Owner owner = maid.getOwner();
        if (ownerStateConditions(owner, maid)) {
            // 如果女仆在前往呼吸点，玩家不在水中
            if (maid.isGoingToBreath()) {
                return !owner.isUnderWater();
            }
            return true;
        }
        return false;
    }

    public void start(FollowOwnerActor maid) {
        Owner owner = maid.getOwner();

        // 如果女仆在前往呼吸点快要淹死了，那必须就近传送
        // 这个传送会鬼畜，但是没办法，为了救女仆只能这样了
        if (maid.isGoingToBreath() && ownerStateConditions(owner, maid)
            && maidStateConditions(maid)
            && maid.teleportToOwner(owner)) {
            maid.resetNavigation();
            maid.setGoingToBreath(false);
            maid.eraseWalkTarget();
            maid.eraseTargetPos();
            maid.stopFollowingTask();
            return;
        }

        // 否则正常传送
        int startDistance = (int) maid.getRestrictRadius() - 2;
        int minTeleportDistance = startDistance + 4;
        if (ownerStateConditions(owner, maid) && maidStateConditions(maid) && !maid.closerThan(owner, startDistance)) {
            if (!maid.closerThan(owner, minTeleportDistance)) {
                maid.teleportToOwner(owner);
                maid.resetNavigation();
            } else if (!maid.ownerIsWalkTarget(owner)) {
                maid.setWalkAndLookTargetMemories(owner, speedModifier, stopDistance);
            }
        }
    }

    private boolean maidStateConditions(FollowOwnerActor maid) {
        return !maid.isHomeModeEnable() && maid.canBrainMoving();
    }

    private boolean ownerStateConditions(Owner owner, FollowOwnerActor maid) {
        return owner != null && !owner.isSpectator()
            && !owner.isDeadOrDying()
            && maid.getWorldIdentity() == owner.getWorldIdentity();
    }
}
