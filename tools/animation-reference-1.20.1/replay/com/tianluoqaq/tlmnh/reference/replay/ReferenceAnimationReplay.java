package com.tianluoqaq.tlmnh.reference.replay;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Arrays;
import java.util.TreeMap;

import net.minecraft.util.ResourceLocation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.client.render.BuiltinGeckoAnimations;
import com.tianluoqaq.tlmnh.client.render.BuiltinGeckoPlayer;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModels;
import com.tianluoqaq.tlmnh.client.render.MaidAnimationState;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 仅显式测试任务编译，不进入GTNH分发JAR；结果只覆盖CPU动画路径 */
public final class ReferenceAnimationReplay {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String BASE = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6";
    private static final List<String> MODELS = Arrays.asList("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid");

    private ReferenceAnimationReplay() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Expected input capture, output report and optional fault");
        String fault = args.length == 3 ? args[2] : "none";
        require("none".equals(fault) || "head_offset".equals(fault), "Unknown replay fault");
        Path input = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        if (input.equals(output)) throw new IllegalArgumentException("Cannot overwrite the original capture");
        var reference = new JsonParser().parse(new String(Files.readAllBytes(input), StandardCharsets.UTF_8)).getAsJsonObject();
        require("observation-v0".equals(reference.get("schema").getAsString()), "Unsupported capture schema");
        String inputMode = reference.get("inputMode").getAsString();
        boolean walkingFixture = "controlled-idle-walk-idle-v1".equals(inputMode);
        require(walkingFixture || "controlled-idle-v0".equals(inputMode), "Unreviewed controlled fixture");
        require(BASE.equals(reference.get("upstreamCommit").getAsString()), "Unexpected upstream source");
        require("none".equals(reference.get("faultInjection").getAsString()), "Faulted reference is not a baseline");
        var frames = reference.getAsJsonArray("frames");
        require(frames.size() > 1 && frames.size() <= 240, "Invalid frame count");
        require(!walkingFixture || frames.size() == 72, "Walk fixture requires exactly 72 steps");
        String id = frames.get(0).getAsJsonObject().get("modelChoice").getAsString();
        require(MODELS.contains(id), "Unreviewed model fixture");
        BuiltinModelCatalog.load();
        var choice = BuiltinModelCatalog.get(id);
        require(choice != null && id.equals(choice.getId()), "Missing selected builtin model");
        var models = new BuiltinMaidModels(ReferenceAnimationReplay::resource);
        var model = models.create(choice);
        var animations = new BuiltinGeckoAnimations(ReferenceAnimationReplay::resource);
        var player = choice.isGecko()
            ? new BuiltinGeckoPlayer(model.getGecko(), animations.load(choice), reference.get("seed").getAsLong()) : null;
        var result = new JsonObject();
        result.addProperty("schema", "port-replay-v0");
        result.addProperty("scope", "controlled empty-equipment CPU replay only; no navigation or GPU acceptance");
        result.addProperty("inputMode", inputMode);
        result.addProperty("referenceSha256", sha256(Files.readAllBytes(input)));
        result.addProperty("upstreamCommit", BASE);
        result.addProperty("modelChoice", id);
        result.addProperty("faultInjection", fault);
        result.addProperty("javaRuntime", System.getProperty("java.runtime.version"));
        result.addProperty("renderingVerified", false);
        result.addProperty("equivalenceVerified", false);
        var assets = new JsonObject();
        for (var location : choice.getAnimations()) asset(assets, location);
        asset(assets, choice.getGeometry());
        asset(assets, choice.getTexture());
        asset(assets, new ResourceLocation(choice.getGeometry().getResourceDomain(), "maid_model.json"));
        if (choice.isGecko()) asset(assets, BuiltinGeckoAnimations.DEFAULT_MAID_ANIMATION);
        result.add("assetSha256", assets);
        var classes = new JsonObject();
        for (Class<?> type : new Class<?>[] { ReferenceAnimationReplay.class, BuiltinGeckoPlayer.class,
            com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController.class,
            com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.AnimationProcessor.class,
            com.tianluoqaq.tlmnh.client.render.BuiltinBedrockAnimations.class, MaidAnimationState.class, MolangQueryFrame.class }) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                require(stream != null, "Missing actual replay class: " + type.getName());
                classes.addProperty(type.getName(), sha256(bytes(stream)));
            }
        }
        result.add("classSha256", classes);
        var replayed = new JsonArray();
        for (int index = 0; index < frames.size(); index++) {
            var original = frames.get(index).getAsJsonObject();
            require(id.equals(original.get("modelChoice").getAsString()) && !original.get("fallback").getAsBoolean(), "Model fallback or switch");
            require(original.get("tick").getAsInt() == index, "Unexpected reference step");
            var inputs = original.getAsJsonObject("inputs");
            String contract = walkingFixture ? "controlled-empty-idle-walk-idle-v1" : "controlled-empty-idle-v1";
            require(inputs != null && contract.equals(inputs.get("contract").getAsString()), "Missing reviewed inputs");
            var stateJson = inputs.getAsJsonObject("state");
            shape(MaidAnimationState.class, stateJson);
            var state = GSON.fromJson(stateJson, MaidAnimationState.class);
            require(state.uuid.toString().equals(reference.get("entityUuid").getAsString()), "Wrong entity identity");
            emptyGroundActor(state);
            require(inputs.getAsJsonObject("equipment").entrySet().size() == 6, "Missing equipment slots");
            for (var slot : inputs.getAsJsonObject("equipment").entrySet())
                require("empty".equals(slot.getValue().getAsString()), "Only empty equipment is reviewed");
            var render = inputs.getAsJsonObject("render");
            float limb = number(render, "limbSwing"), amount = number(render, "limbSwingAmount");
            float age = number(render, "ageInTicks"), yaw = number(render, "netHeadYaw"), pitch = number(render, "headPitch");
            float partial = number(render, "partialTick");
            boolean walking = walkingFixture && index >= 24 && index < 48;
            float expectedAmount = walking ? 0.25F : 0;
            require(age == index && partial == 0 && amount == expectedAmount && yaw == 0 && pitch == 0, "Unreviewed render inputs");
            if (walkingFixture)
                require(limb == Math.min(Math.max(index - 24, 0), 24) * 0.25F, "Unexpected walk phase");
            var frame = new JsonObject();
            frame.addProperty("tick", index);
            frame.addProperty("backend", choice.isGecko() ? "gecko" : "bedrock");
            frame.add("consumedState", GSON.toJsonTree(state));
            var consumedRender = new JsonObject();
            consumedRender.addProperty("limbSwing", limb);
            consumedRender.addProperty("limbSwingAmount", amount);
            consumedRender.addProperty("ageInTicks", age);
            consumedRender.addProperty("netHeadYaw", yaw);
            consumedRender.addProperty("headPitch", pitch);
            consumedRender.addProperty("partialTick", partial);
            frame.add("consumedRender", consumedRender);
            if (player != null) {
                var queryJson = inputs.getAsJsonObject("query");
                shape(MolangQueryFrame.class, queryJson);
                var query = GSON.fromJson(queryJson, MolangQueryFrame.class);
                require(queryJson.getAsJsonObject("equipment").entrySet().size() == 0, "Nonempty query equipment");
                require(query.lifetime == index / 20.0, "Reference lifetime disagrees with step");
                if (walkingFixture) {
                    require(query.groundSpeed == (walking ? 1.25 : 0), "Unexpected walk speed query");
                    require(query.x - query.previousX == (walking ? 0.0625 : 0)
                        && query.y == query.previousY && query.z == query.previousZ
                        && query.verticalSpeed == 0, "Unexpected synthetic walk displacement");
                }
                frame.add("consumedQuery", GSON.toJsonTree(query));
                player.advanceClock(age, false);
                require(player.apply(state, query, limb, amount, partial, false), "Port skipped animation update");
                frame.addProperty("seekTicks", player.getSeekTicks());
                var controllers = new JsonArray();
                for (var controller : player.getAnimationData().getAnimationControllers()) {
                    var layer = new JsonObject();
                    layer.addProperty("name", controller.getName());
                    layer.addProperty("state", controller.getAnimationState().name());
                    layer.addProperty("clip", controller.getCurrentAnimation() == null ? null : controller.getCurrentAnimation().animationName);
                    layer.addProperty("tickOffset", controller.tickOffset);
                    layer.addProperty("speed", controller.animationSpeed);
                    controllers.add(layer);
                }
                frame.add("controllers", controllers);
            } else model.setupPose(state, limb, amount, age, yaw, pitch);
            if ("head_offset".equals(fault)) {
                require(model.getGecko() != null && model.getGecko().head() != null, "Fault requires a Gecko head");
                var head = model.getGecko().head();
                head.setPositionX(head.getPositionX() + 0.25F);
            }
            frame.add("bones", bones(model));
            replayed.add(frame);
        }
        result.add("frames", replayed);
        Files.createDirectories(output.getParent());
        Files.write(output, (GSON.toJson(result) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("TLMNH_REFERENCE_REPLAY_CAPTURED model=" + id + " frames=" + replayed.size() + " equivalence=false rendering=false");
    }

    private static void shape(Class<?> type, JsonObject object) {
        require(object != null, "Missing input object: " + type.getSimpleName());
        int count = 0;
        for (var field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            require(object.has(field.getName()) && !object.get(field.getName()).isJsonNull(), "Missing captured field: " + field.getName());
            count++;
        }
        require(object.entrySet().size() == count, "Unexpected captured fields: " + type.getSimpleName());
    }

    private static void emptyGroundActor(MaidAnimationState state) {
        require(state.onGround && !state.sleeping && !state.sitting && !state.passenger && !state.begging
            && !state.usingItem && !state.usingOffhand && !state.swingLeft && !state.aimingBow && !state.mainHandPresent
            && !state.helmet && !state.chestPlate && !state.leggings && !state.boots && !state.hurt && !state.sprinting
            && !state.climbing && !state.swimming && !state.dead && !state.swinging && state.attackAnim == 0
            && state.vehicleId.isEmpty() && state.passengerId.isEmpty() && "idle".equals(state.task), "Unreviewed actor state");
    }

    private static JsonObject bones(BuiltinMaidModel model) {
        var bones = new JsonObject();
        if (model.getGecko() != null) new TreeMap<>(model.getGecko().bones()).forEach((key, bone) -> {
            var pose = new JsonObject();
            pose.add("translation", vector(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ()));
            pose.add("rotationRadians", vector(bone.getRotationX(), bone.getRotationY(), bone.getRotationZ()));
            pose.add("scale", vector(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()));
            pose.add("pivot", vector(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ()));
            pose.addProperty("hidden", bone.isHidden());
            pose.addProperty("cubesHidden", bone.cubesAreHidden());
            bones.add(key, pose);
        });
        else new TreeMap<>(model.getBedrock().getModelMap()).forEach((key, bone) -> {
            var pose = new JsonObject();
            pose.add("position", vector(bone.x, bone.y, bone.z));
            pose.add("offset", vector(bone.offsetX, bone.offsetY, bone.offsetZ));
            pose.add("rotationRadians", vector(bone.xRot, bone.yRot, bone.zRot));
            pose.add("scale", vector(bone.xScale, bone.yScale, bone.zScale));
            var q = bone.additionalQuaternion;
            pose.add("additionalQuaternion", vector(q.x, q.y, q.z, q.w));
            pose.addProperty("visible", bone.visible);
            bones.add(key, pose);
        });
        return bones;
    }

    private static JsonArray vector(double... values) {
        var result = new JsonArray();
        for (double value : values) {
            require(Double.isFinite(value), "Non-finite port pose");
            result.add(new JsonPrimitive(value));
        }
        return result;
    }

    private static float number(JsonObject object, String name) {
        double value = object.get(name).getAsDouble();
        require(Double.isFinite(value) && Float.isFinite((float) value), "Invalid render input: " + name);
        return (float) value;
    }

    private static InputStream resource(ResourceLocation location) {
        return ReferenceAnimationReplay.class.getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
    }

    private static void asset(JsonObject assets, ResourceLocation location) throws Exception {
        // Bedrock清单的内置Java动画ID没有同名脚本资源，源码版本另由Git固定
        if (location.getResourcePath().endsWith(".js")) return;
        try (var stream = resource(location)) {
            require(stream != null, "Missing actual replay asset: " + location);
            assets.addProperty(location.toString(), sha256(bytes(stream)));
        }
    }

    private static byte[] bytes(InputStream stream) throws java.io.IOException {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
