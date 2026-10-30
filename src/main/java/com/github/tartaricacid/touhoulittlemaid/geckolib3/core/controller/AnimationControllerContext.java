/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/controller/AnimationControllerContext.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure query snapshots and Java 8; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller;

public class AnimationControllerContext {

    private double animTime;

    public void setAnimTime(double animTime) {
        this.animTime = animTime;
    }

    public double animTime() {
        return animTime;
    }
}
