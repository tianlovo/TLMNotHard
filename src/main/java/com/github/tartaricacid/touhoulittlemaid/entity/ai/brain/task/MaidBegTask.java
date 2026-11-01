/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidBegTask.java, MIT
 * Adaptation: captured visible-owner memory and existing WALK/LOOK/pose metadata
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidLifeActor;

public final class MaidBegTask {

    public boolean checkExtraStartConditions(MaidLifeActor maid) {
        return maid.temptingVisibleOwner() != null;
    }

    public boolean canStillUse(MaidLifeActor maid) {
        return checkExtraStartConditions(maid);
    }

    public void tick(MaidLifeActor maid) {
        var owner = maid.getOwner();
        if (owner != null) {
            if (!maid.closerThan(owner, 2)) maid.setWalkAndLookTargetMemories(owner, .6F, 2);
            else maid.lookAtOwner(owner);
            maid.setBegging(true);
        }
    }

    public void stop(MaidLifeActor maid) {
        maid.setBegging(false);
    }
}
