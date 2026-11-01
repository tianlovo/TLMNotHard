/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidWorkMealTask.java, MIT
 * Adaptation: existing inventory/food/use controller executes the source hand/backpack transaction
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidMealActor;

public final class MaidWorkMealTask extends MaidCheckRateTask {

    public MaidWorkMealTask() {
        setMaxCheckRate(50);
    }

    public boolean checkExtraStartConditions(MaidMealActor maid) {
        return super.checkExtraStartConditions(maid) && !maid.isSleeping()
            && maid.taskAllowsEating()
            && maid.canAddWorkMeal();
    }

    public void start(MaidMealActor maid) {
        maid.eatMeal(false);
    }
}
