/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/processor/IBone.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneSnapshot;

public interface IBone extends ILocationBone {

    void setRotationX(float value);

    void setRotationY(float value);

    void setRotationZ(float value);

    void setPositionX(float value);

    void setPositionY(float value);

    void setPositionZ(float value);

    void setScaleX(float value);

    void setScaleY(float value);

    void setScaleZ(float value);

    boolean isHidden();

    void setHidden(boolean hidden);

    boolean cubesAreHidden();

    boolean childBonesAreHiddenToo();

    void setCubesHidden(boolean hidden);

    void setHidden(boolean selfHidden, boolean skipChildRendering);

    BoneSnapshot getInitialSnapshot();

    String getName();
}
