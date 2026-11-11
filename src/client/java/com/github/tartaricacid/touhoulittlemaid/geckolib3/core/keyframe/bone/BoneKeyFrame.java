/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/bone/BoneKeyFrame.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.MathUtil;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

public abstract class BoneKeyFrame {

    protected final double startTick;
    protected final double totalTick;
    protected final Vector3v beginPoint;

    public BoneKeyFrame(double startTick, double totalTick, Vector3v beginPoint) {
        this.startTick = startTick;
        this.totalTick = totalTick;
        this.beginPoint = beginPoint;
    }

    public double getStartTick() {
        return startTick;
    }

    public double getTotalTick() {
        return totalTick;
    }

    public abstract Vector3f getLerpPoint(ExpressionEvaluator<?> evaluator, double percentCompleted);

    public Vector3f getTransitionPoint(ExpressionEvaluator<?> evaluator, Vector3f offsetPoint,
        double percentCompleted) {
        if (isBegin(percentCompleted)) {
            return offsetPoint;
        }
        if (isEnd(percentCompleted)) {
            return beginPoint.eval(evaluator);
        }
        return MathUtil.lerpValues(percentCompleted, offsetPoint, beginPoint.eval(evaluator));
    }

    protected static boolean isBegin(double percentCompleted) {
        return percentCompleted < 0.00001;
    }

    protected static boolean isEnd(double percentCompleted) {
        return percentCompleted > 0.99999;
    }
}
