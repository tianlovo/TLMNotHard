/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/builder/AnimationBuilder.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder;

import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;

public class AnimationBuilder {

    private final List<RawAnimation> animationList = new java.util.ArrayList<>();

    public AnimationBuilder addAnimation(String animationName, ILoopType loopType) {
        animationList.add(new RawAnimation(animationName, loopType));
        return this;
    }

    public AnimationBuilder addAnimation(String animationName) {
        animationList.add(new RawAnimation(animationName, null));
        return this;
    }

    public AnimationBuilder addRepeatingAnimation(String animationName, int timesToRepeat) {
        assert timesToRepeat > 0;
        for (int i = 0; i < timesToRepeat; i++) {
            addAnimation(animationName, EDefaultLoopTypes.PLAY_ONCE);
        }
        return this;
    }

    public AnimationBuilder playOnce(String animationName) {
        return this.addAnimation(animationName, EDefaultLoopTypes.PLAY_ONCE);
    }

    public AnimationBuilder loop(String animationName) {
        return this.addAnimation(animationName, EDefaultLoopTypes.LOOP);
    }

    public AnimationBuilder playAndHold(String animationName) {
        return this.addAnimation(animationName, EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
    }

    public AnimationBuilder clearAnimations() {
        animationList.clear();
        return this;
    }

    public List<RawAnimation> getRawAnimationList() {
        return animationList;
    }
}
