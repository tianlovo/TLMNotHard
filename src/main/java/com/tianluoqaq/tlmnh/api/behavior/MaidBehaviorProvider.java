package com.tianluoqaq.tlmnh.api.behavior;

/** 行为模块注册描述符；同一阶段内按 (order, id) 确定性排序 */
public interface MaidBehaviorProvider {

    MaidBehaviorId id();

    MaidBehaviorStage stage();

    default int order() {
        return BehaviorApiTuning.BEHAVIOR_PROVIDER_ORDER_PARAM;
    }

    MaidBehaviorModule create(MaidBehaviorInitContext init);
}
