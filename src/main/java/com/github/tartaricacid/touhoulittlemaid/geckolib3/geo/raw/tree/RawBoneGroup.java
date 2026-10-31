/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/raw/tree/RawBoneGroup.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API; preserve native fastutil sibling iteration order using the RC2-provided library
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree;

import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Bone;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public class RawBoneGroup {

    public Map<String, RawBoneGroup> children = new Object2ObjectOpenHashMap<>();
    public Bone selfBone;

    public RawBoneGroup(Bone bone) {
        this.selfBone = bone;
    }
}
