/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidSnowballTargetTask.java, MIT
 * Adaptation: native ownerless projectile, hand/animation operations through guarded current-frame actor
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidSnowballActor;

public final class MaidSnowballTargetTask {

    private static final float CHANCE_STOPPING = 1 / 32F;
    private final int attackCooldown;
    private boolean canThrow;
    private int attackTime = -1, playPickUpAnimationDelayTime = -1;

    public MaidSnowballTargetTask(int attackCooldown) {
        this.attackCooldown = attackCooldown;
    }

    public boolean checkExtraStartConditions(MaidSnowballActor maid) {
        var target = maid.attackTarget();
        return target != null && maid.holdingSnowballOrEmpty()
            && maid.targetVisible(target)
            && maid.targetWithinRestriction(target);
    }

    public boolean canStillUse(MaidSnowballActor maid) {
        if (maid.nextSnowballFloat() <= CHANCE_STOPPING) return false;
        var target = maid.attackTarget();
        return target != null && maid.targetInSameWorld(target)
            && maid.targetAlive(target)
            && checkExtraStartConditions(maid);
    }

    public void start(MaidSnowballActor maid) {
        maid.prepareSnowballHand();
    }

    public void tick(MaidSnowballActor maid) {
        var target = maid.attackTarget();
        if (target == null) return;
        if (canThrow && maid.targetVisible(target)) {
            canThrow = false;
            maid.swingSnowballHand();
            maid.lookAtAttackTarget(target);
            maid.throwSnowballAt(target);
            if (!maid.isCurrent()) return;
            attackTime = attackCooldown + maid.nextSnowballInt(attackCooldown);
            playPickUpAnimationDelayTime = 25;
        } else if (--attackTime <= 0) canThrow = true;
        if (playPickUpAnimationDelayTime >= 0) playPickUpAnimationDelayTime--;
        if (playPickUpAnimationDelayTime == 0) maid.playPickUpSnowball();
        if (maid.isPickingUpSnowball()) {
            if (maid.nowMillis() - maid.snowballAnimationStartedAt() > 1750) maid.expireSnowballAnimation();
            maid.stopNavigation();
        }
    }

    public void stop(MaidSnowballActor maid) {
        cancelUseState();
        maid.eraseAttackTarget();
    }

    /** token已经撤销时只清本行为状态，目标记忆由持有者按身份释放 */
    public void cancelUseState() {
        canThrow = false;
    }
}
