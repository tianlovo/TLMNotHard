/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidStealEdibleUseTask.java, MIT
 * Adaptation: native cake and cancellable finite-inventory placement; current-frame checks after callbacks
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidStealActor;

public final class MaidStealEdibleUseTask {

    private final double closeEnoughDist;

    public MaidStealEdibleUseTask(double closeEnoughDist) {
        this.closeEnoughDist = closeEnoughDist;
    }

    public boolean checkExtraStartConditions(MaidStealActor maid) {
        var target = maid.workTarget();
        if (target == null) return false;
        if (maid.distanceToBlockCenterSquared(target) > closeEnoughDist * closeEnoughDist) {
            if (!maid.walkMatchesWorkTarget()) maid.eraseTargetPos();
            return false;
        }
        return true;
    }

    public void start(MaidStealActor maid) {
        var target = maid.workTarget();
        if (target == null || maid.edibleAction() == null) return;
        if (maid.edibleAction() == MaidEdibleBlockAction.TRY_STEAL) {
            if (!maid.shouldStealCake(target)) return;
            boolean used = maid.consumeCake(target);
            if (!maid.isCurrent()) return;
            if (used) {
                maid.rewardSteal();
                maid.swingMainHand();
            }
        } else {
            if (maid.firstCakeStack() == null) return;
            boolean used = maid.placeCake(target);
            if (!maid.isCurrent()) return;
            if (used) maid.swingMainHand();
        }
        maid.eraseTargetPos();
        maid.eraseWalkTarget();
    }
}
