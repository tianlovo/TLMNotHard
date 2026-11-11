package com.tianluoqaq.tlmnh.common.ai.enhanced.session;

import java.util.Objects;
import java.util.function.Consumer;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorGroup;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;
import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorOfferSink;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Group;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;

/** 外部行为模块单帧提案入口；把公开行为组映射到内部仲裁组，并将目标键隔离到模块命名空间 */
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
    public <P> void offer(MaidBehaviorGroup group, String target, double utility, P payload,
        Consumer<? super P> executeAction) {
        var frame = requireActiveFrame();
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(executeAction, "executeAction");
        Group internalGroup = switch (group) {
            case DANGER -> Group.DANGER;
            case RECOVERY -> Group.RECOVERY;
            case COMBAT -> Group.COMBAT;
            case CARE -> Group.CARE;
            case AUTONOMOUS -> Group.AUTONOMOUS;
            case AMBIENT -> Group.AMBIENT;
        };
        var dispatcher = wrappedExecutorFactory;
        String namespacedTarget = moduleId + "|" + target;
        frame.offerTyped(
            moduleId,
            internalGroup,
            Kind.EXTENSION,
            namespacedTarget,
            target,
            utility,
            payload,
            value -> dispatcher.accept(() -> executeAction.accept(value)));
    }
}
