/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/functions/physics/IPhysics.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.physics;

public interface IPhysics {

    void update(float timeStep);

    void setArgs(float arg0, float arg1, float arg2, float arg3);

    float getValue();
}
