package com.tianluoqaq.tlmnh.reference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.capability.GeckoMaidEntityCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 可行性实验的只读观测器，输出不作为完整输入重放或等价性通过凭据 */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class ReferenceObservationProbe {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static Path root;
    private static Capture active;
    private static boolean ioFailed;
    private static Reload pendingReload;
    private static int resourceGeneration, lastControlledGeneration;

    private ReferenceObservationProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("tlmnh.referenceProbe") || ioFailed || event.phase != TickEvent.Phase.END) return;
        try {
            if (root == null) {
                root = Minecraft.getInstance().gameDirectory.toPath().resolve("tlmnh-reference");
                for (String directory : List.of("control", "responses", "captures")) Files.createDirectories(root.resolve(directory));
                TouhouLittleMaid.LOGGER.info("TLMNH_REFERENCE_PROBE_READY schema=observation-v0");
            }
            if (pendingReload != null && pendingReload.future().isDone()) {
                var response = new JsonObject();
                try {
                    pendingReload.future().join();
                    response.addProperty("status", "complete");
                    response.addProperty("resourceGeneration", ++resourceGeneration);
                } catch (RuntimeException error) {
                    response.addProperty("status", "failed");
                    response.addProperty("error", error.toString());
                }
                write(root.resolve("responses").resolve(pendingReload.request()), response);
                pendingReload = null;
            }
            if (active != null) {
                String failure = ReferenceScene.failure(active.entity);
                if (failure != null) active.failure = failure;
            }
            if (active != null && (active.frames.size() == active.limit || active.failure != null || System.nanoTime() > active.deadline)) {
                var response = new JsonObject();
                boolean complete = active.frames.size() == active.limit && active.failure == null;
                response.addProperty("status", complete ? "complete" : "failed");
                response.addProperty("capturedFrames", active.frames.size());
                response.addProperty("replayReady", false);
                response.addProperty("faultInjection", active.fault);
                if (complete) {
                    var report = new JsonObject();
                    report.addProperty("schema", "observation-v0");
                    report.addProperty("upstreamCommit", System.getProperty("tlmnh.upstreamCommit"));
                    report.addProperty("entityUuid", active.entity.toString());
                    report.addProperty("allInputsCaptured", false);
                    report.addProperty("equivalenceVerified", false);
                    report.addProperty("inputMode", active.controlled ? "controlled-idle-v0" : "live-observation");
                    report.addProperty("seed", active.seed);
                    report.addProperty("resourceGeneration", resourceGeneration);
                    report.addProperty("faultInjection", active.fault);
                    var frames = new JsonArray();
                    active.frames.forEach(frames::add);
                    report.add("frames", frames);
                    write(root.resolve("captures").resolve(active.request), report);
                    response.addProperty("capture", "captures/" + active.request);
                } else response.addProperty("error", active.failure != null ? active.failure : "Requested entity did not produce enough rendered frames before timeout");
                write(root.resolve("responses").resolve(active.request), response);
                active = null;
            }
            try (var files = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
                var requests = new ArrayList<Path>();
                for (var file : files) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) requests.add(file);
                requests.sort(Comparator.comparing(path -> path.getFileName().toString()));
                for (int index = 0; index < Math.min(4, requests.size()); index++) handle(requests.get(index));
            }
        } catch (IOException error) {
            ioFailed = true;
            TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_PROBE_FAILED", error);
        }
    }

    private static void handle(Path path) throws IOException {
        String name = path.getFileName().toString();
        var response = new JsonObject();
        try {
            if (!name.matches("[A-Za-z0-9_-]{1,100}\\.json") || Files.size(path) > 16384) throw new IllegalArgumentException("Invalid request file");
            var request = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var minecraft = Minecraft.getInstance();
            switch (request.get("command").getAsString()) {
                case "status" -> {
                    response.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
                    response.addProperty("worldLoaded", minecraft.level != null);
                    response.addProperty("playerPresent", minecraft.player != null);
                    response.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName());
                    response.addProperty("captureActive", active != null);
                    response.addProperty("reloadActive", pendingReload != null);
                    response.addProperty("maidCount", maidCount());
                    response.add("scene", ReferenceScene.status());
                    if (minecraft.player != null) {
                        response.addProperty("health", minecraft.player.getHealth());
                        response.addProperty("spectator", minecraft.player.isSpectator());
                        response.addProperty("invulnerable", minecraft.player.getAbilities().invulnerable);
                        response.addProperty("flying", minecraft.player.getAbilities().flying);
                    }
                    var mods = new JsonArray();
                    var ids = ModList.get().getMods().stream().map(info -> info.getModId()).sorted().toList();
                    ids.forEach(mods::add);
                    response.add("loadedMods", mods);
                    response.addProperty("minimalReferenceMods", Set.of("minecraft", "forge", "touhou_little_maid").equals(Set.copyOf(ids)));
                }
                case "observe" -> {
                    if (minecraft.level == null || active != null || pendingReload != null) throw new IllegalStateException("A world must be loaded and capture must be idle");
                    int count = request.get("frames").getAsInt();
                    if (count < 1 || count > 240 || request.get("frames").getAsDouble() != count) throw new IllegalArgumentException("Invalid frame count");
                    active = new Capture(name, UUID.fromString(request.get("entityUuid").getAsString()), count, false, 0);
                    response.addProperty("status", "waiting-render");
                }
                case "create_lab" -> response = ReferenceScene.createLab();
                case "open_lab" -> response = ReferenceScene.openLab();
                case "spawn_case" -> response = ReferenceScene.spawn(request);
                case "clear_scene" -> {
                    if (active != null || pendingReload != null) throw new IllegalStateException("Finish the current experiment first");
                    response = ReferenceScene.clear();
                }
                case "reload" -> {
                    if (active != null || pendingReload != null || maidCount() != 0) throw new IllegalStateException("Clear the scene before reloading reference resources");
                    pendingReload = new Reload(name, minecraft.reloadResourcePacks());
                    response.addProperty("status", "waiting-reload");
                }
                case "sample_case" -> {
                    if (active != null || pendingReload != null || maidCount() != 0 || resourceGeneration <= lastControlledGeneration)
                        throw new IllegalStateException("Clear the scene and reload resources before each controlled case");
                    int count = request.get("frames").getAsInt();
                    if (count < 1 || count > 240 || request.get("frames").getAsDouble() != count) throw new IllegalArgumentException("Invalid frame count");
                    long seed = request.get("seed").getAsBigDecimal().longValueExact();
                    String fault = request.has("fault") ? request.get("fault").getAsString() : "none";
                    if (!Set.of("none", "head_offset", "clock_offset", "wrong_clip").contains(fault))
                        throw new IllegalArgumentException("Unknown experiment fault");
                    UUID entity = UUID.nameUUIDFromBytes(("tlmnh-reference:" + request.get("model").getAsString()).getBytes(StandardCharsets.UTF_8));
                    ReferenceScene.spawn(request, entity, () -> {
                        active = new Capture(name, entity, count, true, seed);
                        active.expectedModel = request.get("model").getAsString();
                        active.fault = fault;
                    });
                    lastControlledGeneration = resourceGeneration;
                    response.addProperty("status", "waiting-render");
                }
                case "shutdown" -> minecraft.stop();
                default -> throw new IllegalArgumentException("Unknown reference observation command");
            }
            if (!response.has("status")) response.addProperty("status", "complete");
        } catch (RuntimeException error) {
            response.addProperty("status", "failed");
            response.addProperty("error", error.toString());
        }
        Files.move(path, path.resolveSibling(name + ".done"), StandardCopyOption.REPLACE_EXISTING);
        write(root.resolve("responses").resolve(name), response);
    }

    @SubscribeEvent
    public static void rendered(RenderLivingEvent.Post<?, ?> event) {
        if (!Boolean.getBoolean("tlmnh.referenceProbe") || active == null || active.frames.size() >= active.limit
            || !(event.getEntity() instanceof EntityMaid maid) || !maid.getUUID().equals(active.entity)) return;
        try {
            if (active.controlled && (maid.tickCount != active.frames.size() || event.getPartialTick() != 0
                || !maid.onGround() || maid.getXRot() != 0 || maid.yHeadRot != maid.yBodyRot))
                throw new IllegalStateException("Controlled renderer inputs were not applied");
            var frame = new JsonObject();
            frame.addProperty("phase", "render-post");
            frame.addProperty("tick", maid.tickCount);
            frame.addProperty("partialTick", event.getPartialTick());
            frame.addProperty("modelChoice", maid.getModelId());
            frame.addProperty("limbPosition", maid.walkAnimation.position(event.getPartialTick()));
            frame.addProperty("limbSpeed", maid.walkAnimation.speed(event.getPartialTick()));
            frame.addProperty("sitting", maid.isMaidInSittingPose());
            frame.addProperty("onGround", maid.onGround());
            frame.addProperty("headPitch", maid.getXRot());
            frame.addProperty("netHeadYaw", maid.yHeadRot - maid.yBodyRot);
            frame.add("position", vector(maid.getX(), maid.getY(), maid.getZ()));
            var bones = new JsonObject();
            if (event.getRenderer() instanceof GeckoEntityMaidRenderer<?>) {
                var animatable = maid.getCapability(GeckoMaidEntityCapabilityProvider.CAP).orElse(null);
                if (animatable == null || animatable.getCurrentModel() == null) throw new IllegalStateException("Missing actual Gecko model");
                frame.addProperty("backend", "gecko");
                frame.addProperty("modelResource", animatable.getModelLocation().toString());
                frame.addProperty("fallback", animatable.getMaidInfo() == null
                    || !animatable.getModelLocation().equals(animatable.getMaidInfo().getModelId()));
                frame.addProperty("textureResource", animatable.getTextureLocation().toString());
                frame.addProperty("seekTicks", animatable.getSeekTime());
                frame.addProperty("animationResource", animatable.getAnimationFileLocation().toString());
                if (active.controlled && animatable.getSeekTime() != active.frames.size())
                    throw new IllegalStateException("Original seek time did not match the controlled frame: expected="
                        + active.frames.size() + " actual=" + animatable.getSeekTime());
                new TreeMap<>(animatable.getCurrentModel().bones()).forEach((key, bone) -> {
                    var pose = new JsonObject();
                    pose.add("translation", vector(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ()));
                    pose.add("rotationRadians", vector(bone.getRotationX(), bone.getRotationY(), bone.getRotationZ()));
                    pose.add("scale", vector(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()));
                    pose.add("pivot", vector(bone.getPivotX(), bone.getPivotY(), bone.getPivotZ()));
                    pose.addProperty("hidden", bone.isHidden());
                    pose.addProperty("cubesHidden", bone.cubesAreHidden());
                    bones.add(key, pose);
                });
                var controllers = new JsonArray();
                for (var controller : animatable.getAnimationData().getAnimationControllers()) {
                    var state = new JsonObject();
                    state.addProperty("name", controller.getName());
                    state.addProperty("state", controller.getAnimationState().name());
                    state.addProperty("clip", controller.getCurrentAnimation() == null ? null : controller.getCurrentAnimation().animationName);
                    state.addProperty("tickOffset", controller.tickOffset);
                    state.addProperty("speed", controller.animationSpeed);
                    controllers.add(state);
                }
                frame.add("controllers", controllers);
            } else if (event.getRenderer() instanceof EntityMaidRenderer renderer) {
                frame.addProperty("backend", "bedrock");
                frame.addProperty("fallback", CustomPackLoader.MAID_MODELS.getModel(maid.getModelId())
                    .map(expected -> expected != renderer.getModel()).orElse(true));
                frame.addProperty("textureResource", renderer.getTextureLocation(maid).toString());
                new TreeMap<>(renderer.getModel().getModelMap()).forEach((key, bone) -> {
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
            } else throw new IllegalStateException("Unexpected original maid renderer");
            if (bones.size() == 0) throw new IllegalStateException("Empty original bone snapshot");
            if (active.controlled && frame.get("fallback").getAsBoolean()) throw new IllegalStateException("Controlled case used a fallback model");
            frame.add("bones", bones);
            active.frames.add(frame);
        } catch (RuntimeException error) {
            TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_CAPTURE_FAILED", error);
            active.failure = error.toString();
        }
    }

    private static JsonArray vector(double... values) {
        var array = new JsonArray();
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite original pose value");
            array.add(value);
        }
        return array;
    }

    public static ReferenceFrameScope beginFrame(Mob entity, float partialTick) {
        if (!Boolean.getBoolean("tlmnh.referenceProbe") || active == null || !active.controlled
            || active.frames.size() >= active.limit || !entity.getUUID().equals(active.entity))
            return ReferenceFrameScope.uncontrolled(partialTick);
        if (Minecraft.getInstance().isPaused() || !(entity instanceof EntityMaid maid) || !ReferenceScene.isCurrent(maid)
            || !maid.isTame() || !maid.getModelId().equals(active.expectedModel)) return ReferenceFrameScope.skip(partialTick);
        try {
            var scope = ReferenceFrameScope.controlled(maid, active.frames.size(), active.seed, !active.seeded);
            active.seeded = true;
            return scope;
        } catch (RuntimeException error) {
            active.failure = error.toString();
            return ReferenceFrameScope.skip(partialTick);
        }
    }

    private static boolean hasFault(Entity entity, String fault) {
        return Boolean.getBoolean("tlmnh.referenceProbe") && active != null && active.controlled
            && active.frames.size() < active.limit && active.fault.equals(fault)
            && entity.getUUID().equals(active.entity) && entity instanceof EntityMaid maid && ReferenceScene.isCurrent(maid);
    }

    public static double animationTick(Entity entity, double original) {
        return hasFault(entity, "clock_offset") ? original + 1 : original;
    }

    public static String mainAnimationName(Entity entity, String original) {
        return hasFault(entity, "wrong_clip") ? "walk" : original;
    }

    public static void afterAnimation(Entity entity, AnimatedGeoModel model) {
        if (!hasFault(entity, "head_offset")) return;
        if (model == null || model.head() == null) {
            active.failure = "Required fault injection bone is missing";
            return;
        }
        var head = model.head();
        head.setPositionX(head.getPositionX() + 0.25F);
    }

    private static int maidCount() {
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) for (var entity : level.entitiesForRendering()) if (entity instanceof EntityMaid) count++;
        return count;
    }

    private static void write(Path target, JsonObject value) throws IOException {
        var temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class Capture {
        final String request;
        final UUID entity;
        final int limit;
        final boolean controlled;
        final long seed;
        final List<JsonObject> frames = new ArrayList<>();
        long deadline = System.nanoTime() + 60_000_000_000L;
        boolean seeded;
        String failure;
        String expectedModel;
        String fault = "none";

        Capture(String request, UUID entity, int limit, boolean controlled, long seed) {
            this.request = request;
            this.entity = entity;
            this.limit = limit;
            this.controlled = controlled;
            this.seed = seed;
        }
    }

    private record Reload(String request, CompletableFuture<Void> future) {}
}
