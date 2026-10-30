/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/bone/CatmullRomKeyFrame.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util.MathUtil;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

public class CatmullRomKeyFrame extends BoneKeyFrame {

    private final Vector3v leftPoint;
    private final Vector3v endPoint;
    private final Vector3v rightPoint;
    private final Vector3v postPoint;

    public CatmullRomKeyFrame(double startTick, double totalTick, Vector3v leftPoint, Vector3v current,
        Vector3v endPoint, Vector3v postRight, Vector3v postPoint) {
        super(startTick, totalTick, current);
        this.leftPoint = leftPoint;
        this.endPoint = endPoint;
        this.rightPoint = postRight;
        this.postPoint = postPoint;
    }

    @Override
    public Vector3f getLerpPoint(ExpressionEvaluator<?> evaluator, double percentCompleted) {
        if (isBegin(percentCompleted)) {
            return beginPoint.eval(evaluator);
        }
        if (isEnd(percentCompleted)) {
            return postPoint.eval(evaluator);
        }
        Vector3f left = leftPoint.eval(evaluator);
        Vector3f begin = beginPoint.eval(evaluator);
        Vector3f end = endPoint.eval(evaluator);
        Vector3f right = rightPoint.eval(evaluator);
        return MathUtil.catmullRom(percentCompleted, left, begin, end, right);
    }
}
