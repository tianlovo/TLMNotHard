/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidCheckRateTask.java, MIT
 * Adaptation: guarded meal actor; Behavior lifecycle remains in the component
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidMealActor;

public abstract class MaidCheckRateTask {

    private int maxCheckRate = 20;
    private int nextCheckTickCount;

    protected boolean checkExtraStartConditions(MaidMealActor maid) {
        if (nextCheckTickCount > 0) {
            --nextCheckTickCount;
            return false;
        }
        nextCheckTickCount = maxCheckRate + maid.nextMealInt(maxCheckRate);
        return true;
    }

    protected void setMaxCheckRate(int rate) {
        maxCheckRate = rate;
    }

    public void reset() {
        nextCheckTickCount = 0;
    }
}
