/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/KeyFramePoint.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationControllerContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;

public class KeyFramePoint extends AnimationPoint {

    public final BoneKeyFrame keyframe;

    public KeyFramePoint(double currentTick, BoneKeyFrame keyframe, AnimationControllerContext context) {
        super(currentTick, keyframe.getTotalTick(), context);
        this.keyframe = keyframe;
    }

    @Override
    public Vector3f getLerpPoint(ExpressionEvaluator<MolangAnimationContext<?>> evaluator) {
        setupControllerContext(evaluator);
        return keyframe.getLerpPoint(evaluator, getPercentCompleted());
    }
}
