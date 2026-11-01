/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/ride/MaidRideBegTask.java, MIT
 * Adaptation: guarded visible-owner memory; mounted variant only writes LOOK
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ride;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidLifeActor;

public final class MaidRideBegTask {

    public boolean checkExtraStartConditions(MaidLifeActor maid) {
        return maid.temptingVisibleOwner() != null;
    }

    public boolean canStillUse(MaidLifeActor maid) {
        return checkExtraStartConditions(maid);
    }

    public void tick(MaidLifeActor maid) {
        var owner = maid.getOwner();
        if (owner != null) {
            maid.lookAtOwner(owner);
            maid.setBegging(true);
        }
    }

    public void stop(MaidLifeActor maid) {
        maid.setBegging(false);
    }
}
