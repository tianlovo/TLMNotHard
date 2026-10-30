package com.tianluoqaq.tlmnh.reference;

import java.util.Map;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.UseAnim;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.TLMBinding;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.YSMBinding;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.ContextBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.QueryBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.AnimationContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.variable.ContextVariable;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** 从实际调用读取输入；只为空手动画夹具提供有界合同，不填造未知状态 */
public final class ReferenceReplayInputs {
    private ReferenceReplayInputs() {}

    /** 在客户端tick读取实际资源管理器，避免在渲染回调做文件I/O */
    public static JsonObject assets(String modelId) throws IOException {
        var info = CustomPackLoader.MAID_MODELS.getInfo(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Missing original model metadata: " + modelId));
        var result = new JsonObject();
        Path root;
        try (var files = Files.list(CustomPackLoader.PACK_FOLDER)) {
            var candidates = files.filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(".zip")).toList();
            if (candidates.size() != 1 || !candidates.get(0).getFileName().toString().equals("touhou_little_maid-1.0.0"))
                throw new IOException("Reference fixture requires the single original default model pack");
            root = candidates.get(0).toRealPath();
            if (!root.startsWith(CustomPackLoader.PACK_FOLDER.toRealPath())) throw new IOException("Unexpected model pack target");
        }
        hashPack(result, root, info.getModel());
        hashPack(result, root, info.getTexture());
        hashPack(result, root, new ResourceLocation(info.getModelId().getNamespace(), "maid_model.json"));
        for (var animation : info.getAnimation()) if (!animation.getPath().endsWith(".js")) hashPack(result, root, animation);
        if (info.isGeckoModel()) hashResource(result, new ResourceLocation("touhou_little_maid", "animation/maid.animation.json"));
        return result;
    }

    private static void hashPack(JsonObject assets, Path root, ResourceLocation location) throws IOException {
        // CommonDefaultPack解包后，CustomPackLoader从这个唯一模型包读取，而非常规资源管理器
        Path path = root.resolve("assets").resolve(location.getNamespace()).resolve(location.getPath()).toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new IOException("Unexpected actual pack asset: " + location);
        assets.addProperty(location.toString(), digest(Files.readAllBytes(path)));
    }

    private static void hashResource(JsonObject assets, ResourceLocation location) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location)
            .orElseThrow(() -> new IOException("Missing actual original asset: " + location));
        try (var stream = resource.open()) {
            assets.addProperty(location.toString(), digest(stream.readAllBytes()));
        }
    }

    private static String digest(byte[] bytes) {
        try {
            var hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static JsonObject capture(EntityMaid maid, float limb, float amount, float age, float yaw, float pitch,
                                     float partial, AnimationContext<?> context, String scenario) {
        var result = new JsonObject();
        result.addProperty("contract", ReferenceScenario.contract(scenario));
        var render = new JsonObject();
        render.addProperty("limbSwing", limb);
        render.addProperty("limbSwingAmount", amount);
        render.addProperty("ageInTicks", age);
        render.addProperty("netHeadYaw", yaw);
        render.addProperty("headPitch", pitch);
        render.addProperty("partialTick", partial);
        result.add("render", render);
        var state = new JsonObject();
        state.addProperty("uuid", maid.getUUID().toString());
        state.addProperty("sleeping", maid.isSleeping());
        state.addProperty("sitting", maid.isMaidInSittingPose());
        state.addProperty("passenger", maid.isPassenger());
        state.addProperty("begging", maid.isBegging());
        state.addProperty("usingItem", maid.isUsingItem());
        state.addProperty("usingOffhand", maid.isUsingItem() && maid.getUsedItemHand() == InteractionHand.OFF_HAND);
        state.addProperty("swingLeft", maid.swinging && maid.swingingArm == InteractionHand.OFF_HAND);
        state.addProperty("aimingBow", maid.isUsingItem() && maid.getUseItem().getUseAnimation() == UseAnim.BOW);
        state.addProperty("mainHandPresent", !maid.getMainHandItem().isEmpty());
        state.addProperty("helmet", !maid.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
        state.addProperty("chestPlate", !maid.getItemBySlot(EquipmentSlot.CHEST).isEmpty());
        state.addProperty("leggings", !maid.getItemBySlot(EquipmentSlot.LEGS).isEmpty());
        state.addProperty("boots", !maid.getItemBySlot(EquipmentSlot.FEET).isEmpty());
        state.addProperty("hurt", maid.hurtTime > 0);
        state.addProperty("onGround", maid.onGround());
        state.addProperty("sprinting", maid.isSprinting());
        state.addProperty("climbing", maid.onClimbable());
        state.addProperty("swimming", maid.isSwimming());
        state.addProperty("dead", maid.isDeadOrDying());
        state.addProperty("swinging", maid.swinging);
        state.addProperty("vehicleId", entityId(maid.getVehicle()));
        state.addProperty("passengerId", entityId(maid.getFirstPassenger()));
        state.addProperty("swingTick", maid.swingTime);
        state.addProperty("verticalSpeed", 20 * (maid.getY() - maid.yo));
        state.addProperty("attackAnim", maid.getAttackAnim(partial));
        state.addProperty("health", maid.getHealth());
        state.addProperty("maxHealth", maid.getMaxHealth());
        String dimension = maid.level().dimension().location().toString();
        state.addProperty("dimension", switch (dimension) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> -1;
            case "minecraft:the_end" -> 1;
            default -> throw new IllegalStateException("Unmapped reference dimension: " + dimension);
        });
        state.addProperty("dayTime", Math.floorMod(maid.level().getDayTime(), 24000L));
        state.addProperty("task", maid.getTask().getUid().getPath());
        result.add("state", state);
        var equipment = new JsonObject();
        for (var slot : EquipmentSlot.values()) {
            var stack = maid.getItemBySlot(slot);
            if (!stack.isEmpty()) throw new IllegalStateException("Empty-hand fixture has equipment: " + slot);
            equipment.addProperty(slot.getName(), "empty");
        }
        result.add("equipment", equipment);
        if (context != null) result.add("query", query(maid, context));
        return result;
    }

    private static JsonObject query(EntityMaid maid, AnimationContext<?> context) {
        var query = new JsonObject();
        var names = Map.ofEntries(
            Map.entry("lifetime", "life_time"), Map.entry("bodyYaw", "body_y_rotation"),
            Map.entry("netHeadYaw", "head_x_rotation"), Map.entry("headPitch", "head_y_rotation"),
            Map.entry("health", "health"), Map.entry("maxHealth", "max_health"),
            Map.entry("groundSpeed", "ground_speed"), Map.entry("verticalSpeed", "vertical_speed"),
            Map.entry("yawSpeed", "yaw_speed"), Map.entry("inWater", "is_in_water"),
            Map.entry("inWaterOrRain", "is_in_water_or_rain"), Map.entry("jumping", "is_jumping"),
            Map.entry("sneaking", "is_sneaking"));
        names.forEach((field, name) -> query.add(field, evaluate(QueryBinding.INSTANCE, name, context)));
        query.add("begging", evaluate(TLMBinding.INSTANCE, "is_begging", context));
        query.add("sitting", evaluate(TLMBinding.INSTANCE, "is_sitting", context));
        query.add("hasBackpack", evaluate(TLMBinding.INSTANCE, "has_backpack", context));
        query.add("closeEyes", evaluate(YSMBinding.INSTANCE, "is_close_eyes", context));
        query.add("hasElytra", evaluate(YSMBinding.INSTANCE, "has_elytra", context));
        query.add("foodLevel", evaluate(YSMBinding.INSTANCE, "food_level", context));
        query.addProperty("viewYaw", maid.getViewYRot(context.animationEvent().getPartialTick()));
        query.addProperty("previousX", maid.xo);
        query.addProperty("previousY", maid.yo);
        query.addProperty("previousZ", maid.zo);
        query.addProperty("x", maid.getX());
        query.addProperty("y", maid.getY());
        query.addProperty("z", maid.getZ());
        query.addProperty("partialTick", context.animationEvent().getPartialTick());
        query.add("equipment", new JsonObject());
        return query;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonPrimitive evaluate(ContextBinding binding, String name, AnimationContext<?> context) {
        if (!(binding.getProperty(name) instanceof ContextVariable variable))
            throw new IllegalStateException("Missing original query: " + name);
        Object value = variable.evaluate(context);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return new JsonPrimitive(number);
        throw new IllegalStateException("Unsupported original query value: " + name + "=" + value);
    }

    private static String entityId(Entity entity) {
        return entity == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
