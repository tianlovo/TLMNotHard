/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/util/json/JsonAnimationUtils.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections, Gson 2.2.4 and strict Molang parsing; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.github.tartaricacid.touhoulittlemaid.geckolib3.util.json;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.ILoopType;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.BoneAnimation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrameProcessor;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.RawBoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.event.EventKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.MolangParser;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.AnimationUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class JsonAnimationUtils {

    public static Set<Map.Entry<String, JsonElement>> getAnimations(JsonObject json) {
        if (json.has("animations")) {
            return json.getAsJsonObject("animations")
                .entrySet();
        }
        return ImmutableSet.of();
    }

    public static List<Map.Entry<String, JsonElement>> getBones(JsonObject json) {
        JsonObject bones = json.getAsJsonObject("bones");
        return bones == null ? java.util.Collections.emptyList() : new ArrayList<>(bones.entrySet());
    }

    public static List<Map.Entry<String, JsonElement>> getCustomInstructionKeyFrames(JsonObject json) {
        JsonObject customInstructions = json.getAsJsonObject("timeline");
        return customInstructions == null ? java.util.Collections.emptyList()
            : new ArrayList<>(customInstructions.entrySet());
    }

    private static JsonElement getObjectByKey(Set<Map.Entry<String, JsonElement>> json, String key)
        throws JsonParseException {
        for (Map.Entry<String, JsonElement> entry : json) {
            if (entry.getKey()
                .equals(key)) {
                return entry.getValue();
            }
        }
        throw new JsonParseException("Could not find key: " + key);
    }

    public static Map.Entry<String, JsonElement> getAnimation(JsonObject animationFile, String animationName)
        throws JsonParseException {
        return new AbstractMap.SimpleEntry(animationName, getObjectByKey(getAnimations(animationFile), animationName));
    }

    public static Animation deserializeJsonToAnimation(Map.Entry<String, JsonElement> element, MolangParser parser)
        throws ClassCastException, IllegalStateException {
        JsonObject animationJsonObject = element.getValue()
            .getAsJsonObject();

        // 设置有关动画的一些 metadata
        var animationName = element.getKey();
        JsonElement animationLength = animationJsonObject.get("animation_length");
        var animationLengthTicks = animationLength == null ? -1
            : AnimationUtils.convertSecondsToTicks(animationLength.getAsDouble());

        var loop = ILoopType.fromJson(animationJsonObject.get("loop"));
        var boneAnimations = new ArrayList<BoneAnimation>();
        var customInstructionKeyframes = new ArrayList<EventKeyFrame<IValue[]>>();

        // 处理自定义指令关键帧
        for (Map.Entry<String, JsonElement> keyFrame : getCustomInstructionKeyFrames(animationJsonObject)) {
            double startTick = Double.parseDouble(keyFrame.getKey()) * 20;
            JsonElement value = keyFrame.getValue();
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                IValue[] values = new IValue[array.size()];
                for (int i = 0; i < array.size(); i++) {
                    String parserText = array.get(i)
                        .getAsString();
                    values[i] = parser.parseExpressionRequired(parserText);
                }
                customInstructionKeyframes.add(new EventKeyFrame<>(startTick, values));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive()
                .isString()) {
                    IValue[] values = new IValue[] { parser.parseExpressionRequired(value.getAsString()) };
                    customInstructionKeyframes.add(new EventKeyFrame<>(startTick, values));
                }
        }
        // 排序，因为 json 读取是乱序的
        customInstructionKeyframes.sort(Comparator.comparingDouble(EventKeyFrame::getStartTick));

        // 此动画中使用的所有骨骼的列表
        for (Map.Entry<String, JsonElement> bone : getBones(animationJsonObject)) {

            List<RawBoneKeyFrame> rotationKeyFrames = Lists.newArrayList();
            List<RawBoneKeyFrame> positionKeyFrames = Lists.newArrayList();
            List<RawBoneKeyFrame> scaleKeyFrames = Lists.newArrayList();
            JsonObject boneJsonObj = bone.getValue()
                .getAsJsonObject();

            JsonKeyFrameUtils.getKeyFrames(scaleKeyFrames, boneJsonObj.get("scale"), parser);
            JsonKeyFrameUtils.getKeyFrames(positionKeyFrames, boneJsonObj.get("position"), parser);
            JsonKeyFrameUtils.getKeyFrames(rotationKeyFrames, boneJsonObj.get("rotation"), parser);

            boneAnimations.add(
                new BoneAnimation(
                    bone.getKey(),
                    BoneKeyFrameProcessor.process(rotationKeyFrames, true),
                    BoneKeyFrameProcessor.process(positionKeyFrames, false),
                    BoneKeyFrameProcessor.process(scaleKeyFrames, false)));
        }
        if (animationLengthTicks == -1) {
            animationLengthTicks = calculateLength(boneAnimations);
        }
        return new Animation(animationName, animationLengthTicks, loop, boneAnimations, customInstructionKeyframes);
    }

    private static double calculateLength(List<BoneAnimation> boneAnimations) {
        double longestLength = 0;
        for (BoneAnimation animation : boneAnimations) {
            double xKeyframeTime = calculateKeyFrameListLength(animation.rotationKeyFrames);
            double yKeyframeTime = calculateKeyFrameListLength(animation.positionKeyFrames);
            double zKeyframeTime = calculateKeyFrameListLength(animation.scaleKeyFrames);
            longestLength = maxAll(longestLength, xKeyframeTime, yKeyframeTime, zKeyframeTime);
        }
        return longestLength == 0 ? Double.MAX_VALUE : longestLength;
    }

    private static double calculateKeyFrameListLength(List<BoneKeyFrame> boneKeyFrames) {
        if (boneKeyFrames.isEmpty()) {
            return 0;
        }
        var last = boneKeyFrames.get(boneKeyFrames.size() - 1);
        return last.getStartTick() + last.getTotalTick();
    }

    public static double maxAll(double... values) {
        double max = 0;
        for (double value : values) {
            max = Math.max(value, max);
        }
        return max;
    }
}
