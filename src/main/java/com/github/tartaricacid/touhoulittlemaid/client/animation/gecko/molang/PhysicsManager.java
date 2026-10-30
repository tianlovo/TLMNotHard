/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/PhysicsManager.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.physics.IPhysics;

public class PhysicsManager {

    private final java.util.HashMap<String, IPhysics> physicsValues = new java.util.HashMap<>(16);
    private float lastRenderTicks = Float.NaN;

    public void update(float renderTicks) {
        if (!Float.isFinite(renderTicks) || renderTicks < 0) return;
        if (Float.isFinite(lastRenderTicks)) {
            if (renderTicks > lastRenderTicks) {
                float interval = (renderTicks - lastRenderTicks) / 20f;
                lastRenderTicks = renderTicks;
                physicsValues.forEach((key, value) -> value.update(interval));
            }
        } else {
            lastRenderTicks = renderTicks;
        }
    }

    public void put(String key, IPhysics physics) {
        this.physicsValues.put(key, physics);
    }

    public IPhysics get(String key) {
        return this.physicsValues.get(key);
    }

    public void reset() {
        lastRenderTicks = Float.NaN;
        physicsValues.clear();
    }
}
