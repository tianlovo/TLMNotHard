/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/manager/AnimationData.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.manager;

import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;

@SuppressWarnings("rawtypes")
public class AnimationData {

    private final List<AnimationController> animationControllers = new java.util.ArrayList<>(32);
    public double tick;
    public boolean isFirstTick = true;
    public double startTick = -1;
    public boolean shouldPlayWhilePaused = false;
    private double resetTickLength = 1;

    public AnimationData() {}

    public AnimationController addAnimationController(AnimationController value) {
        animationControllers.add(value);
        return value;
    }

    public double getResetSpeed() {
        return resetTickLength;
    }

    /**
     * 这是任何没有动画的骨骼恢复到其初始位置所需的时间
     *
     * @param resetTickLength 重置时所需的 tick。不能为负数
     */
    public void setResetSpeedInTicks(double resetTickLength) {
        if (!Double.isFinite(resetTickLength)) throw new IllegalArgumentException("Reset duration must be finite");
        this.resetTickLength = resetTickLength < 0 ? 0 : resetTickLength;
    }

    public List<AnimationController> getAnimationControllers() {
        return animationControllers;
    }
}
