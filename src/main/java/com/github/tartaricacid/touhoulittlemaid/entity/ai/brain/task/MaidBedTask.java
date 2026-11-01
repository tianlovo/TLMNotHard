/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidBedTask.java, MIT
 * Adaptation: loaded bed-head TE index replaces POI; guarded WORK_TARGET and shared bed lease
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidLifeActor;

public final class MaidBedTask extends MaidCheckRateTask {

    private final int closeEnoughDist;
    private final float speed;

    public MaidBedTask(float movementSpeed, int closeEnoughDist) {
        this.closeEnoughDist = closeEnoughDist;
        speed = movementSpeed;
        setMaxCheckRate(10);
    }

    public boolean checkExtraStartConditions(MaidLifeActor maid) {
        if (super.checkExtraStartConditions(maid::nextLifeInt) && maid.canBrainMoving()) {
            var pos = maid.findNearestBed();
            if (pos != null && maid.withinLifeRestriction(pos)) {
                if (maid.distanceToBlockCenterSquared(pos) < closeEnoughDist * closeEnoughDist) {
                    maid.setWorkTarget(pos);
                    return true;
                }
                maid.setPositionWalkAndLook(pos, speed, 1);
                setNextCheckTickCount(5);
            } else maid.eraseTargetPos();
        }
        return false;
    }

    public void start(MaidLifeActor maid) {
        var pos = maid.workTarget();
        if (pos != null) maid.sleepAt(pos);
        if (!maid.isCurrent()) return;
        maid.eraseTargetPos();
        maid.eraseWalkTarget();
        maid.eraseLookTarget();
    }
}
