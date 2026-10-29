/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/animated/ILocationModel.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated;

import java.util.Collections;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.ILocationBone;

public interface ILocationModel {

    default List<? extends ILocationBone> leftHandBones() {
        return Collections.emptyList();
    }

    default List<List<? extends ILocationBone>> extraLeftHandBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> rightHandBones() {
        return Collections.emptyList();
    }

    default List<List<? extends ILocationBone>> extraRightHandBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> leftWaistBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> rightWaistBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> backpackBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> tacPistolBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> tacRifleBones() {
        return Collections.emptyList();
    }

    default List<? extends ILocationBone> headBones() {
        return Collections.emptyList();
    }
}
