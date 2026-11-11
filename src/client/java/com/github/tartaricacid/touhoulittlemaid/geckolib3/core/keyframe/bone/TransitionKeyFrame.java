/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/bone/TransitionKeyFrame.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

public class TransitionKeyFrame extends BoneKeyFrame {

    private final Vector3v postPoint;

    public TransitionKeyFrame(double firstStartTick, Vector3v firstPoint, Vector3v postPoint) {
        super(0, firstStartTick, firstPoint);
        this.postPoint = postPoint;
    }

    @Override
    public Vector3f getLerpPoint(ExpressionEvaluator<?> evaluator, double percentCompleted) {
        if (!isEnd(percentCompleted)) {
            return beginPoint.eval(evaluator);
        } else {
            return postPoint.eval(evaluator);
        }
    }
}
