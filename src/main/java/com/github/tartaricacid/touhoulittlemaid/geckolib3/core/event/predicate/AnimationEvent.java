/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/event/predicate/AnimationEvent.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate;

import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.tianluoqaq.tlmnh.client.render.GeckoAnimationTarget;

public class AnimationEvent<T extends GeckoAnimationTarget> {

    private final T animatable;
    private final float limbSwing;
    private final float limbSwingAmount;
    private final float partialTick;
    private final boolean isMoving;
    private final List<Object> extraData;
    public double animationTick;
    protected AnimationController<T> controller;

    public AnimationEvent(T animatable, float limbSwing, float limbSwingAmount, float partialTick, boolean isMoving,
        List<Object> extraData) {
        this.animatable = animatable;
        this.limbSwing = limbSwing;
        this.limbSwingAmount = limbSwingAmount;
        this.partialTick = partialTick;
        this.isMoving = isMoving;
        this.extraData = extraData;
    }

    /**
     * 以动画控制的状态，获取当前动画时间，或者过渡动画时间
     */
    public double getAnimationTick() {
        return animationTick;
    }

    public T getAnimatableEntity() {
        return animatable;
    }

    public float getLimbSwing() {
        return limbSwing;
    }

    public float getLimbSwingAmount() {
        return limbSwingAmount;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public AnimationController<T> getController() {
        return controller;
    }

    public void setController(AnimationController<T> controller) {
        this.controller = controller;
    }

    public List<Object> getExtraData() {
        return extraData;
    }

    @SuppressWarnings("unchecked")
    public <D> List<D> getExtraDataOfType(Class<D> type) {
        java.util.ArrayList<D> matches = new java.util.ArrayList<>();
        for (Object obj : this.extraData) {
            if (type.isAssignableFrom(obj.getClass())) {
                matches.add((D) obj);
            }
        }
        return matches;
    }
}
