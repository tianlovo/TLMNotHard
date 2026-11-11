package com.tianluoqaq.tlmnh.client.render;

import java.util.List;

import org.joml.Vector3f;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

/** 在原关键帧定义上定位时间段，不保存实体或上一次采样状态 */
public final class GeckoKeyframes {

    private GeckoKeyframes() {}

    public static Vector3f sample(List<BoneKeyFrame> frames, double tick, ExpressionEvaluator<?> evaluator) {
        if (frames.isEmpty()) throw new IllegalArgumentException("Missing keyframes");
        if (!Double.isFinite(tick)) throw new IllegalArgumentException("Non-finite animation tick");
        tick = Math.max(0, tick);
        int low = 0, high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle)
                .getStartTick() <= tick) low = middle + 1;
            else high = middle;
        }
        var frame = frames.get(Math.max(0, low - 1));
        double duration = frame.getTotalTick();
        double progress = duration <= 0 ? 1 : Math.max(0, Math.min(1, (tick - frame.getStartTick()) / duration));
        return frame.getLerpPoint(evaluator, progress);
    }
}
