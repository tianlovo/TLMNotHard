package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.Objects;
import java.util.function.Consumer;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorOfferSink;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 外部行为模块单帧提案入口；强制阻断 EMERGENCY 与 COMMAND 保留分组并将目标键隔离到模块命名空间 */
public final class MaidExternalOfferSink implements MaidBehaviorOfferSink {

    private final MaidBehaviorId moduleId;
    private final MaidSessionCallbackScope scope;
    private MaidIntentFrame activeFrame;
    private Consumer<Runnable> wrappedExecutorFactory;

    public MaidExternalOfferSink(MaidBehaviorId moduleId, MaidSessionCallbackScope scope) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public void bindFrame(MaidIntentFrame frame, Consumer<Runnable> executorHook) {
        this.activeFrame = Objects.requireNonNull(frame, "frame");
        this.wrappedExecutorFactory = Objects.requireNonNull(executorHook, "executorHook");
    }

    public void clearFrame() {
        this.activeFrame = null;
        this.wrappedExecutorFactory = null;
    }

    private MaidIntentFrame requireActiveFrame() {
        scope.requireOffer(moduleId);
        var frame = activeFrame;
        if (frame == null || !frame.isCurrent())
            throw new IllegalStateException("Offer frame is no longer current for " + moduleId);
        return frame;
    }

    @Override
    public boolean hasOffers() {
        return requireActiveFrame().hasOffers();
    }

    @Override
    public boolean hasNonAmbientOffer() {
        return requireActiveFrame().hasNonAmbientOffer();
    }

    @Override
    public <P> void offer(Group group, String target, double utility, P payload, Consumer<? super P> executeAction) {
        var frame = requireActiveFrame();
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(executeAction, "executeAction");
        if (group == Group.EMERGENCY || group == Group.COMMAND) throw new IllegalArgumentException(
            "External behavior module " + moduleId + " cannot offer into reserved group " + group);
        var dispatcher = wrappedExecutorFactory;
        String namespacedTarget = moduleId + "|" + target;
        frame.offerTyped(
            moduleId,
            group,
            Kind.EXTENSION,
            namespacedTarget,
            target,
            utility,
            payload,
            value -> dispatcher.accept(() -> executeAction.accept(value)));
    }
}
