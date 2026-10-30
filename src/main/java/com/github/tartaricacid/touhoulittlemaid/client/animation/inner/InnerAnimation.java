/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/inner/InnerAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: legacy resource IDs and per-frame state input; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.inner;

import java.util.HashMap;

import net.minecraft.util.ResourceLocation;

import com.tianluoqaq.tlmnh.client.render.MaidAnimationState;

public final class InnerAnimation {

    static final HashMap<ResourceLocation, IAnimation<MaidAnimationState>> INNER_ANIMATION = new HashMap<>();

    public static boolean containsKey(ResourceLocation resourceLocation) {
        return INNER_ANIMATION.containsKey(resourceLocation);
    }

    public static IAnimation<MaidAnimationState> get(ResourceLocation resourceLocation) {
        return INNER_ANIMATION.get(resourceLocation);
    }

    public static void init() {
        INNER_ANIMATION.clear();
        MaidBaseAnimation.init();
        MaidExtraAnimation.init();
        MaidArmorAnimation.init();
        MaidTaskAnimation.init();
        EntityBaseAnimation.init();
        SpecialAnimation.init();
    }
}
