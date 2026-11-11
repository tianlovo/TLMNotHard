package com.tianluoqaq.tlmnh.api.behavior;

import java.util.Objects;
import java.util.function.Consumer;

import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;

/** 单帧行为提案收集口；禁止外部扩展向 EMERGENCY 与 COMMAND 保留组提交提案 */
public interface MaidBehaviorOfferSink {

    /** 是否已有更高阶段或更早顺序的有效提案入列 */
    boolean hasOffers();

    /** 是否已有非 AMBIENT 分组的提案入列 */
    boolean hasNonAmbientOffer();

    <P> void offer(Group group, String target, double utility, P payload, Consumer<? super P> executeAction);

    default void offer(Group group, String target, double utility, Runnable executeAction) {
        Objects.requireNonNull(executeAction, "executeAction");
        offer(group, target, utility, null, ignored -> executeAction.run());
    }
}
