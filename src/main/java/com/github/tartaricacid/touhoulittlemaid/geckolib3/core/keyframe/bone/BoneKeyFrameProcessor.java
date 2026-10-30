/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/bone/BoneKeyFrameProcessor.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone;

import java.util.List;

public class BoneKeyFrameProcessor {

    public static List<BoneKeyFrame> process(List<RawBoneKeyFrame> frames, boolean isRotation) {
        for (RawBoneKeyFrame frame : frames) {
            frame.init(isRotation);
        }
        BoneKeyFrame[] list = new BoneKeyFrame[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            RawBoneKeyFrame end = frames.get(i);
            // 虽然感觉不太合理，但还是和 BlockBench 保持一致比较好
            EasingType easingType;
            if (end.easingType() == EasingType.CATMULLROM || i == 0) {
                easingType = end.easingType();
            } else {
                easingType = frames.get(i - 1)
                    .easingType();
            }
            list[i] = easingType.buildKeyFrame(frames, i);
        }
        return java.util.Arrays.asList(list);
    }
}
