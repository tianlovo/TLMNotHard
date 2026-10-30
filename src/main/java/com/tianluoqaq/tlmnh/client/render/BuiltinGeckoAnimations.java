package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.MolangParser;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.json.JsonAnimationUtils;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.render.molang.MaidMolangBindings;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 按内置清单顺序合并动画，共享定义，不共享播放进度、骨骼或物理状态 */
public final class BuiltinGeckoAnimations {

    public static final ResourceLocation DEFAULT_MAID_ANIMATION = new ResourceLocation(
        "touhou_little_maid",
        "animation/maid.animation.json");
    private final Map<ResourceLocation, AnimationFile> files = new HashMap<>();
    private final Map<String, AnimationFile> models = new HashMap<>();
    private final MolangParser parser = MaidMolangBindings.parser();
    private BuiltinMaidModels.ResourceOpener opener;

    public BuiltinGeckoAnimations(BuiltinMaidModels.ResourceOpener opener) {
        this.opener = opener;
    }

    public AnimationFile load(BuiltinModelCatalog.Choice choice) throws IOException {
        if (!choice.isGecko()) throw new IllegalArgumentException("Expected Gecko model");
        var cached = models.get(choice.getBaseId());
        if (cached != null) return cached;
        var merged = new LinkedHashMap<String, Animation>();
        for (var location : choice.getAnimations()) {
            merged.putAll(loadFile(location).animations());
        }
        loadFile(DEFAULT_MAID_ANIMATION).animations()
            .forEach(merged::putIfAbsent);
        var result = new AnimationFile(Collections.unmodifiableMap(merged));
        models.put(choice.getBaseId(), result);
        return result;
    }

    private AnimationFile loadFile(ResourceLocation location) throws IOException {
        var file = files.get(location);
        if (file == null) {
            try (var stream = opener.open(location)) {
                if (stream == null) throw new IOException("Missing builtin animation " + location);
                file = read(stream, parser);
            } catch (RuntimeException error) {
                throw new IOException("Cannot parse builtin animation " + location, error);
            }
            files.put(location, file);
        }
        return file;
    }

    public void reload(BuiltinMaidModels.ResourceOpener opener) {
        this.opener = opener;
        files.clear();
        models.clear();
        parser.reset();
    }

    /** 调用方拥有输入流；任何表达式失败都会使该文件加载失败 */
    public static AnimationFile read(InputStream stream, MolangParser parser) {
        var json = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
            .getAsJsonObject();
        if (!json.has("animations") || !json.get("animations")
            .isJsonObject()) throw new IllegalArgumentException("Missing animations object");
        var result = new LinkedHashMap<String, Animation>();
        for (var entry : JsonAnimationUtils.getAnimations(json)) {
            try {
                var animation = JsonAnimationUtils.deserializeJsonToAnimation(entry, parser);
                if (!Double.isFinite(animation.animationLength) || animation.animationLength < 0)
                    throw new IllegalArgumentException("Invalid animation length");
                validate(animation);
                result.put(entry.getKey(), animation);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid animation " + entry.getKey(), error);
            }
        }
        return new AnimationFile(Collections.unmodifiableMap(result));
    }

    private static void validate(Animation animation) {
        for (var bone : animation.boneAnimations) {
            validate(bone.rotationKeyFrames);
            validate(bone.positionKeyFrames);
            validate(bone.scaleKeyFrames);
        }
        for (var event : animation.customInstructionKeyframes)
            if (!Double.isFinite(event.getStartTick()) || event.getStartTick() < 0)
                throw new IllegalArgumentException("Invalid timeline tick");
    }

    private static void validate(List<BoneKeyFrame> frames) {
        for (var frame : frames) {
            if (!Double.isFinite(frame.getStartTick()) || frame.getStartTick() < 0
                || !Double.isFinite(frame.getTotalTick())
                || frame.getTotalTick() < 0) throw new IllegalArgumentException("Invalid bone keyframe tick");
        }
    }
}
