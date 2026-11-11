/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/bone/EasingType.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone;

import java.util.List;

// Native Access：所有字段都有读取
public interface EasingType {

    EasingType LINEAR = EasingType::buildLinearKeyFrame;
    EasingType CATMULLROM = EasingType::buildCatmullRomKeyFrame;

    BoneKeyFrame buildKeyFrame(List<RawBoneKeyFrame> keyFrames, int index);

    static TransitionKeyFrame buildTransitionKeyFrame(List<RawBoneKeyFrame> keyFrames) {
        RawBoneKeyFrame transitionDst = keyFrames.get(0);
        return new TransitionKeyFrame(transitionDst.startTick(), transitionDst.preValue(), transitionDst.postValue());
    }

    static BoneKeyFrame buildLinearKeyFrame(List<RawBoneKeyFrame> keyFrames, int index) {
        if (index == 0) {
            return buildTransitionKeyFrame(keyFrames);
        }
        RawBoneKeyFrame begin = keyFrames.get(index - 1);
        RawBoneKeyFrame end = keyFrames.get(index);
        return new LinearKeyFrame(
            begin.startTick(),
            end.startTick() - begin.startTick(),
            begin.postValue(),
            end.preValue(),
            end.postValue());
    }

    static BoneKeyFrame buildCatmullRomKeyFrame(List<RawBoneKeyFrame> keyFrames, int index) {
        if (index == 0) {
            return buildTransitionKeyFrame(keyFrames);
        }
        RawBoneKeyFrame left = keyFrames.get(Math.max(0, index - 2));
        RawBoneKeyFrame begin = keyFrames.get(index - 1);
        RawBoneKeyFrame end = keyFrames.get(index);
        RawBoneKeyFrame right = keyFrames.get(Math.min(keyFrames.size() - 1, index + 1));

        return new CatmullRomKeyFrame(
            begin.startTick(),
            end.startTick() - begin.startTick(),
            left.postValue(),
            begin.postValue(),
            end.preValue(),
            right.preValue(),
            end.postValue());
    }
}
