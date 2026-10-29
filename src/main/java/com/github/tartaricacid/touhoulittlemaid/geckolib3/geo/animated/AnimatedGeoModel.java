/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/geo/animated/AnimatedGeoModel.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 API, Forge 1.7.10, explicit Gson adapters and legacy matrices
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoModel;

public class AnimatedGeoModel implements ILocationModel {

    private final GeoModel geoModel;
    private final List<AnimatedGeoBone> topLevelBones;
    private final Map<String, AnimatedGeoBone> bones;

    private final List<AnimatedGeoBone> leftHandBones;
    private final List<AnimatedGeoBone> rightHandBones;
    private final List<AnimatedGeoBone> leftWaistBones;
    private final List<AnimatedGeoBone> rightWaistBones;
    private final List<AnimatedGeoBone> backpackBones;
    private final List<AnimatedGeoBone> tacPistolBones;
    private final List<AnimatedGeoBone> tacRifleBones;
    private final List<AnimatedGeoBone> headBones;

    @Nullable
    private final AnimatedGeoBone head;
    @Nullable
    private final AnimatedGeoBone hat;
    @Nullable
    private final AnimatedGeoBone leftArm;
    @Nullable
    private final AnimatedGeoBone rightArm;

    public AnimatedGeoModel(GeoModel model) {
        geoModel = model;

        var bones = new LinkedHashMap<String, AnimatedGeoBone>();
        this.topLevelBones = Collections.unmodifiableList(
            new ArrayList<>(
                model.topLevelBones()
                    .stream()
                    .map(b -> new AnimatedGeoBone(b, bones))
                    .collect(Collectors.toList())));
        this.bones = Collections.unmodifiableMap(bones);

        this.leftHandBones = getLocatorHierarchy("LeftHandLocator");
        this.rightHandBones = getLocatorHierarchy("RightHandLocator");
        this.leftWaistBones = getLocatorHierarchy("LeftWaistLocator");
        this.rightWaistBones = getLocatorHierarchy("RightWaistLocator");
        this.backpackBones = getLocatorHierarchy("BackpackLocator");
        this.tacPistolBones = getLocatorHierarchy("PistolLocator");
        this.tacRifleBones = getLocatorHierarchy("RifleLocator");
        this.headBones = getLocatorHierarchy("Head");

        this.head = bones.get("Head");
        // fixme: 有 hat 部分吗？
        this.hat = bones.get("Hat");
        this.leftArm = bones.get("LeftArm");
        this.rightArm = bones.get("RightArm");
    }

    private List<AnimatedGeoBone> getLocatorHierarchy(String locatorName) {
        var bone = this.bones.get(locatorName);
        if (bone == null) {
            return Collections.emptyList();
        }

        var list = new ArrayList<AnimatedGeoBone>();
        while (true) {
            list.add(bone);
            if (bone.geoBone()
                .parent() != null) {
                bone = Objects.requireNonNull(
                    this.bones.get(
                        bone.geoBone()
                            .parent()
                            .name()));
            } else {
                break;
            }
        }

        Collections.reverse(list);
        return Collections.unmodifiableList(list);
    }

    public GeoModel geoModel() {
        return geoModel;
    }

    public List<AnimatedGeoBone> topLevelBones() {
        return topLevelBones;
    }

    public Map<String, AnimatedGeoBone> bones() {
        return bones;
    }

    public List<AnimatedGeoBone> leftHandBones() {
        return leftHandBones;
    }

    public List<AnimatedGeoBone> rightHandBones() {
        return rightHandBones;
    }

    public List<AnimatedGeoBone> leftWaistBones() {
        return leftWaistBones;
    }

    public List<AnimatedGeoBone> rightWaistBones() {
        return rightWaistBones;
    }

    public List<AnimatedGeoBone> backpackBones() {
        return backpackBones;
    }

    public List<AnimatedGeoBone> tacPistolBones() {
        return tacPistolBones;
    }

    public List<AnimatedGeoBone> tacRifleBones() {
        return tacRifleBones;
    }

    public List<AnimatedGeoBone> headBones() {
        return headBones;
    }

    @Nullable
    public AnimatedGeoBone head() {
        return head;
    }

    @Nullable
    public AnimatedGeoBone hat() {
        return hat;
    }

    @Nullable
    public AnimatedGeoBone leftArm() {
        return leftArm;
    }

    @Nullable
    public AnimatedGeoBone rightArm() {
        return rightArm;
    }
}
