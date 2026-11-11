package com.tianluoqaq.tlmnh.common.ai.faithful.perception;

import java.util.function.IntUnaryOperator;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask;

/**
 * 首版没有野餐垫和游乐设施，但不能删除原任务在查找失败前的检查计数与实体随机数消耗
 * 对应固定上游 MaidFindHomeMealTask/MaidJoyTask/MaidHomeMealTask，空内容没有可启动行为或新记忆
 */
public final class MaidAbsentContentChecks {

    private final Rate findHomeMeal = new Rate(FaithfulPerceptionTuning.ABSENT_CONTENT_RATE_FIRST_PARAM),
        joy = new Rate(FaithfulPerceptionTuning.ABSENT_CONTENT_RATE_FIRST_PARAM),
        homeMeal = new Rate(FaithfulPerceptionTuning.ABSENT_CONTENT_RATE_FIRST_PARAM_2);

    public void visitFindHomeMeal(boolean memoriesAbsent, IntUnaryOperator random) {
        if (memoriesAbsent) findHomeMeal.visit(random);
    }

    public void visitJoy(boolean memoriesAbsent, IntUnaryOperator random) {
        if (memoriesAbsent) joy.visit(random);
    }

    public void visitHomeMeal(IntUnaryOperator random) {
        homeMeal.visit(random);
    }

    public void reset() {
        findHomeMeal.reset();
        joy.reset();
        homeMeal.reset();
    }

    private static final class Rate extends MaidCheckRateTask {

        Rate(int maximum) {
            setMaxCheckRate(maximum);
        }

        void visit(IntUnaryOperator random) {
            checkExtraStartConditions(random);
        }
    }
}
