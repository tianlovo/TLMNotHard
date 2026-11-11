/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/event/ParticleKeyFrameEvent.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;

public class ParticleKeyFrameEvent<T> extends KeyframeEvent<T> {

    public final String effect;
    public final String locator;
    public final String script;

    @SuppressWarnings("rawtypes")
    public ParticleKeyFrameEvent(T entity, double animationTick, String effect, String locator, String script,
        AnimationController controller) {
        super(entity, animationTick, controller);
        this.effect = effect;
        this.locator = locator;
        this.script = script;
    }
}
