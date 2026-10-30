/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/builder/Animation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder;

import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.BoneAnimation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.event.EventKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;

public class Animation {

    public final String animationName;
    public final double animationLength;
    public final ILoopType loop;
    public final List<BoneAnimation> boneAnimations;
    public final List<EventKeyFrame<IValue[]>> customInstructionKeyframes;

    public Animation(String animationName, double animationLength, ILoopType loop, List<BoneAnimation> boneAnimations,
        List<EventKeyFrame<IValue[]>> customInstructionKeyframes) {
        this.animationName = animationName;
        this.animationLength = animationLength;
        this.loop = loop;
        this.boneAnimations = boneAnimations;
        this.customInstructionKeyframes = customInstructionKeyframes;
    }
}
