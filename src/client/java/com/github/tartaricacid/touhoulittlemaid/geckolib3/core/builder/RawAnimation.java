/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/builder/RawAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder;

import java.util.Objects;

public class RawAnimation {

    public final String animationName;
    public final ILoopType loopType;

    /**
     * 仅存储名称和播放循环类型的类
     */
    public RawAnimation(String animationName, ILoopType loop) {
        this.animationName = animationName;
        this.loopType = loop;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof RawAnimation animation)) {
            return false;
        }
        return animation.loopType == this.loopType && animation.animationName.equals(this.animationName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.animationName, this.loopType);
    }
}
