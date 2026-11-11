/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/file/AnimationFile.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.file;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;

public final class AnimationFile {

    private final Map<String, Animation> animations;

    public AnimationFile() {
        this(new LinkedHashMap<>());
    }

    public AnimationFile(Map<String, Animation> animations) {
        this.animations = animations;
    }

    public Map<String, Animation> animations() {
        return animations;
    }

    public Animation getAnimation(String name) {
        return animations.get(name);
    }

    public Collection<Animation> getAllAnimations() {
        return animations.values();
    }

    public void putAnimation(String name, Animation animation) {
        animations.put(name, animation);
    }
}
