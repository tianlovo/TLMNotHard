package com.tianluoqaq.tlmnh.common.ai.faithful;

/** 原进食任务的检查与事务边界，食物仍由同一个真实库存和使用控制器持有 */
public interface MaidMealActor {

    int nextMealInt(int bound);

    boolean isSleeping();

    boolean taskAllowsEating();

    float missingHealth();

    boolean canAddWorkMeal();

    void eatMeal(boolean healing);
}
