package com.tianluoqaq.tlmnh.client.render.animation;

import java.util.List;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

/** 在原关键帧定义上定位时间段，不保存实体或上一次采样状态 */
public final class GeckoKeyframes {

    private GeckoKeyframes() {}

    public static Vector3f sample(List<BoneKeyFrame> frames, double tick, ExpressionEvaluator<?> evaluator) {
        if (frames.isEmpty()) throw new IllegalArgumentException(AnimationDiagnostics.MSG_MISSING_KEYFRAMES);
        if (!Double.isFinite(tick)) throw new IllegalArgumentException(AnimationDiagnostics.MSG_NON_FINITE_ANIMATION_TICK);
        tick = Math.max(AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE, tick);
        int low = 0, high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET;
            if (frames.get(middle)
                .getStartTick() <= tick) low = middle + 1;
            else high = middle;
        }
        var frame = frames.get(Math.max(AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE, low - 1));
        double duration = frame.getTotalTick();
        double progress = duration <= 0 ? AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET : Math.max(AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE, Math.min(AnimationLayout.BEDROCK_ANIMATIONS_LAYOUT_F1_OFFSET, (tick - frame.getStartTick()) / duration));
        return frame.getLerpPoint(evaluator, progress);
    }
}
