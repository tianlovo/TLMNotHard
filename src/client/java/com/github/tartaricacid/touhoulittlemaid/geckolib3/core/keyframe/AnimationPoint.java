/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/AnimationPoint.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationControllerContext;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;

public abstract class AnimationPoint {

    /**
     * 当前关键帧播放进度
     */
    public final double currentTick;
    /**
     * 当前关键帧总长度
     */
    public final double totalTick;
    /**
     * 与动画控制器相关的 molang 上下文
     */
    private final AnimationControllerContext context;

    public AnimationPoint(double currentTick, double totalTick, AnimationControllerContext context) {
        this.currentTick = currentTick;
        this.totalTick = totalTick;
        this.context = context;
    }

    protected double getPercentCompleted() {
        return totalTick == 0 ? 1 : (currentTick / totalTick);
    }

    protected void setupControllerContext(ExpressionEvaluator<MolangAnimationContext<?>> evaluator) {
        evaluator.entity()
            .setAnimationControllerContext(context);
    }

    public abstract Vector3f getLerpPoint(ExpressionEvaluator<MolangAnimationContext<?>> evaluator);
}
