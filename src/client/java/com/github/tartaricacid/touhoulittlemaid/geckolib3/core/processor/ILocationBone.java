/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/processor/ILocationBone.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor;

public interface ILocationBone {

    float getRotationX();

    float getRotationY();

    float getRotationZ();

    float getPositionX();

    float getPositionY();

    float getPositionZ();

    float getScaleX();

    float getScaleY();

    float getScaleZ();

    float getPivotX();

    float getPivotY();

    float getPivotZ();
}
