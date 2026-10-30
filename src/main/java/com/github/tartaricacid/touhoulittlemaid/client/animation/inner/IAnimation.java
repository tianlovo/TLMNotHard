/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/inner/IAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: legacy pose interfaces; animation input can use the port state context
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.inner;

import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;

@FunctionalInterface
public interface IAnimation<T> {

    /**
     * 实体动画
     */
    void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch,
        float scaleFactor, T entityIn, Map<String, ModelRendererWrapper> modelMap);
}
