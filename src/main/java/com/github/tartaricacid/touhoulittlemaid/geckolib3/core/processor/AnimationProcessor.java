/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/processor/AnimationProcessor.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.BoneAnimationQueue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.manager.AnimationData;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneSnapshot;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.MathUtil;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.RateLimiter;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.tianluoqaq.tlmnh.client.render.GeckoAnimationTarget;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class AnimationProcessor<T extends GeckoAnimationTarget> {

    private static final int ROAMING_STRUCT_NAME = StringPool.computeIfAbsent("roaming");

    private final java.util.ArrayList<BoneTopLevelSnapshot> modelRendererList = new java.util.ArrayList<>();
    private final java.util.HashMap<String, BoneTopLevelSnapshot> modelRendererMap = new java.util.HashMap<>();
    private final VariableStorage animationStorage;
    private final ConcurrentLinkedQueue<Pair<IValue, Consumer<String>>> pendingValues = new ConcurrentLinkedQueue<>();
    private final RateLimiter rateLimiter = new RateLimiter(60);
    private final T animatable;

    private List<IValue> initializationValues;
    private List<IValue> preAnimationValues;

    private boolean rendererDirty = false;
    public boolean reloadAnimations = false;

    public AnimationProcessor(T animatable, VariableStorage storage) {
        this.animationStorage = storage;
        this.animatable = animatable;
    }

    @SuppressWarnings("DataFlowIssue")
    public boolean tickAnimation(double seekTime, AnimationEvent<T> event, MolangAnimationContext<?> ctx) {
        var shouldUpdate = rateLimiter.request((float) (seekTime / 20));

        ExpressionEvaluator<MolangAnimationContext<?>> evaluator = ExpressionEvaluator.evaluator(ctx);
        preProcess(evaluator);

        // InstancedAnimationFactory 仅保有一个 AnimationData 实例，与传入的 uniqueID 无关
        AnimationData manager = this.animatable.getAnimationData();
        for (AnimationController<T> controller : manager.getAnimationControllers()) {
            if (reloadAnimations) {
                controller.markNeedsReload();
                controller.getBoneAnimationQueues()
                    .clear();
            }
            controller.isJustStarting = manager.isFirstTick;
            // 将当前控制器设置为动画测试事件
            event.setController(controller);
            // 处理动画并向点队列添加新值
            controller.process(seekTime, event, evaluator, modelRendererList, false, rendererDirty, shouldUpdate);
            boolean isParallelController = controller.getName()
                .startsWith("parallel_");
            // 遍历每个骨骼，并对属性进行插值计算
            for (BoneAnimationQueue boneAnimation : controller.getBoneAnimationQueues()) {
                BoneTopLevelSnapshot snapshot = boneAnimation.topLevelSnapshot;
                BoneSnapshot initialSnapshot = snapshot.bone.getInitialSnapshot();
                PointData pointData = snapshot.cachedPointData;

                // 如果此骨骼有任何旋转值
                if (!boneAnimation.rotationQueue()
                    .isEmpty()) {
                    Vector3f scale = boneAnimation.rotationQueue()
                        .poll()
                        .getLerpPoint(evaluator);
                    pointData.rotationValueX += scale.x();
                    pointData.rotationValueY += scale.y();
                    pointData.rotationValueZ += scale.z();
                    if (isParallelController) {
                        snapshot.rotationValueX = pointData.rotationValueX + initialSnapshot.rotationValueX;
                        snapshot.rotationValueY = pointData.rotationValueY + initialSnapshot.rotationValueY;
                        snapshot.rotationValueZ = pointData.rotationValueZ + initialSnapshot.rotationValueZ;
                    } else {
                        snapshot.rotationValueX = scale.x() + initialSnapshot.rotationValueX;
                        snapshot.rotationValueY = scale.y() + initialSnapshot.rotationValueY;
                        snapshot.rotationValueZ = scale.z() + initialSnapshot.rotationValueZ;
                    }
                    snapshot.isCurrentlyRunningRotationAnimation = true;
                }

                // 如果此骨骼有任何位置值
                if (!boneAnimation.positionQueue()
                    .isEmpty()) {
                    Vector3f position = boneAnimation.positionQueue()
                        .poll()
                        .getLerpPoint(evaluator);
                    snapshot.positionOffsetX = position.x();
                    snapshot.positionOffsetY = position.y();
                    snapshot.positionOffsetZ = position.z();
                    snapshot.isCurrentlyRunningPositionAnimation = true;
                }

                // 如果此骨骼有任何缩放点
                if (!boneAnimation.scaleQueue()
                    .isEmpty()) {
                    Vector3f scale = boneAnimation.scaleQueue()
                        .poll()
                        .getLerpPoint(evaluator);
                    snapshot.scaleValueX = scale.x();
                    snapshot.scaleValueY = scale.y();
                    snapshot.scaleValueZ = scale.z();
                    snapshot.isCurrentlyRunningScaleAnimation = true;
                }
            }
        }

        this.rendererDirty = false;
        this.reloadAnimations = false;

        // 追踪哪些骨骼应用了动画，并最终将没有动画的骨骼设置为默认值
        final double resetTickLength = manager.getResetSpeed();
        for (BoneTopLevelSnapshot topLevelSnapshot : modelRendererList) {
            BoneSnapshot initialSnapshot = topLevelSnapshot.bone.getInitialSnapshot();
            BoneSnapshot lastAnimated = topLevelSnapshot.lastAnimatedSnapshot;

            if (!topLevelSnapshot.isCurrentlyRunningRotationAnimation) {
                double percentageReset = resetProgress(
                    seekTime,
                    topLevelSnapshot.mostRecentResetRotationTick,
                    resetTickLength);
                topLevelSnapshot.rotationValueX = MathUtil
                    .lerpValues(percentageReset, lastAnimated.rotationValueX, initialSnapshot.rotationValueX);
                topLevelSnapshot.rotationValueY = MathUtil
                    .lerpValues(percentageReset, lastAnimated.rotationValueY, initialSnapshot.rotationValueY);
                topLevelSnapshot.rotationValueZ = MathUtil
                    .lerpValues(percentageReset, lastAnimated.rotationValueZ, initialSnapshot.rotationValueZ);
            } else {
                topLevelSnapshot.mostRecentResetRotationTick = seekTime;
                lastAnimated.rotationValueX = topLevelSnapshot.rotationValueX;
                lastAnimated.rotationValueY = topLevelSnapshot.rotationValueY;
                lastAnimated.rotationValueZ = topLevelSnapshot.rotationValueZ;
            }

            if (!topLevelSnapshot.isCurrentlyRunningPositionAnimation) {
                double percentageReset = resetProgress(
                    seekTime,
                    topLevelSnapshot.mostRecentResetPositionTick,
                    resetTickLength);
                topLevelSnapshot.positionOffsetX = MathUtil
                    .lerpValues(percentageReset, lastAnimated.positionOffsetX, initialSnapshot.positionOffsetX);
                topLevelSnapshot.positionOffsetY = MathUtil
                    .lerpValues(percentageReset, lastAnimated.positionOffsetY, initialSnapshot.positionOffsetY);
                topLevelSnapshot.positionOffsetZ = MathUtil
                    .lerpValues(percentageReset, lastAnimated.positionOffsetZ, initialSnapshot.positionOffsetZ);
            } else {
                topLevelSnapshot.mostRecentResetPositionTick = seekTime;
                lastAnimated.positionOffsetX = topLevelSnapshot.positionOffsetX;
                lastAnimated.positionOffsetY = topLevelSnapshot.positionOffsetY;
                lastAnimated.positionOffsetZ = topLevelSnapshot.positionOffsetZ;
            }

            if (!topLevelSnapshot.isCurrentlyRunningScaleAnimation) {
                double percentageReset = resetProgress(
                    seekTime,
                    topLevelSnapshot.mostRecentResetScaleTick,
                    resetTickLength);
                topLevelSnapshot.scaleValueX = MathUtil
                    .lerpValues(percentageReset, lastAnimated.scaleValueX, initialSnapshot.scaleValueX);
                topLevelSnapshot.scaleValueY = MathUtil
                    .lerpValues(percentageReset, lastAnimated.scaleValueY, initialSnapshot.scaleValueY);
                topLevelSnapshot.scaleValueZ = MathUtil
                    .lerpValues(percentageReset, lastAnimated.scaleValueZ, initialSnapshot.scaleValueZ);
            } else {
                topLevelSnapshot.mostRecentResetScaleTick = seekTime;
                lastAnimated.scaleValueX = topLevelSnapshot.scaleValueX;
                lastAnimated.scaleValueY = topLevelSnapshot.scaleValueY;
                lastAnimated.scaleValueZ = topLevelSnapshot.scaleValueZ;
            }

            topLevelSnapshot.isCurrentlyRunningRotationAnimation = false;
            topLevelSnapshot.isCurrentlyRunningPositionAnimation = false;
            topLevelSnapshot.isCurrentlyRunningScaleAnimation = false;
            topLevelSnapshot.commit();
        }
        manager.isFirstTick = false;

        postProcess(evaluator);
        return true;
    }

    public IBone getBone(String boneName) {
        BoneTopLevelSnapshot renderer = modelRendererMap.get(boneName);
        return renderer != null ? renderer.bone : null;
    }

    private static double resetProgress(double tick, double lastAnimatedTick, double duration) {
        return duration <= 0 ? 1 : Math.max(0, Math.min((tick - lastAnimatedTick) / duration, 1));
    }

    public void registerModelRenderer(Map<String, ? extends IBone> boneMap) {
        this.modelRendererMap.clear();
        this.modelRendererList.clear();
        this.modelRendererList.ensureCapacity(boneMap.size());
        for (Map.Entry<String, ? extends IBone> entry : boneMap.entrySet()) {
            BoneTopLevelSnapshot renderer = new BoneTopLevelSnapshot(entry.getValue());
            this.modelRendererMap.put(entry.getKey(), renderer);
            this.modelRendererList.add(renderer);
        }
        this.animationStorage.initialize(null);
        this.rendererDirty = true;
    }

    public boolean isModelRendererEmpty() {
        return modelRendererList.isEmpty();
    }

    public void preAnimationSetup(GeckoAnimationTarget animatable, double seekTime) {}

    private void preProcess(ExpressionEvaluator<MolangAnimationContext<?>> evaluator) {
        if (rendererDirty && initializationValues != null) {
            for (IValue value : initializationValues) {
                value.evalAsDouble(evaluator);
            }
            initializationValues = null;
        }
        if (preAnimationValues != null) {
            for (IValue value : preAnimationValues) {
                value.evalAsDouble(evaluator);
            }
        }
    }

    private void postProcess(ExpressionEvaluator<MolangAnimationContext<?>> evaluator) {
        while (!pendingValues.isEmpty()) {
            Pair<IValue, Consumer<String>> pair = pendingValues.poll();
            String result;
            try {
                var ret = pair.getLeft()
                    .evalUnsafe(evaluator);
                if (ret == null) {
                    result = "null";
                } else if (ret instanceof String) {
                    result = "'" + ret + "'";
                } else {
                    result = ret.toString();
                }
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
            if (pair.getRight() != null) {
                pair.getRight()
                    .accept(result);
            }
        }
    }

    public void execute(IValue value, Consumer<String> resultConsumer) {
        pendingValues.add(Pair.of(value, resultConsumer));
    }

    public IForeignVariableStorage getPublicVariableStorage() {
        return this.animationStorage;
    }
}
