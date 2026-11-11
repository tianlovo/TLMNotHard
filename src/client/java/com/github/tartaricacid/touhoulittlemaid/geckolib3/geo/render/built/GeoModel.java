/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/render/built/GeoModel.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.ModelProperties;

public class GeoModel {

    private final List<GeoBone> topLevelBones;
    private final ModelProperties properties;

    public GeoModel(List<GeoBone> topLevelBones, ModelProperties properties) {
        this.topLevelBones = Collections.unmodifiableList(new ArrayList<>(topLevelBones));
        this.properties = properties;
    }

    public List<GeoBone> topLevelBones() {
        return topLevelBones;
    }

    public ModelProperties properties() {
        return properties;
    }
}
