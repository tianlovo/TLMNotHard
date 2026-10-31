package com.tianluoqaq.tlmnh.client.development;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.BuiltinGeckoPlayer;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemLayer;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.MaidShieldFixtures;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.SideOnly;

/** 一次真实本体之后复绘装备，GPU恢复body color/depth；不重跑动画，不替换物品注册 */
@SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class HeldItemGpuProbe {

    private static final int SIZE = 256;
    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .create();
    private static Request pending;
    private static Transaction active;
    private static boolean factoryCancellationConsumed;
    private static long clientTickEndSequence;

    private HeldItemGpuProbe() {}

    public static boolean isPending() {
        return pending != null;
    }

    public static boolean isCapturing(EntityMaid maid) {
        var transaction = active;
        return transaction != null && transaction.request.maid == maid;
    }

    public static void arm(String request, String model, String action, String view, int warmupTicks) {
        requireObserver();
        if (pending != null || active != null) throw new IllegalStateException("Held GPU probe already pending");
        if (!request.matches("[A-Za-z0-9_-]{1,100}\\.json")
            || !Arrays.asList(EntityMaid.DEFAULT_MODEL_ID, "geckolib:winefox")
                .contains(model)
            || !actions().contains(action)
            || !ClientDevelopmentRequest.heldGpuViews()
                .contains(view)
            || warmupTicks < 0
            || warmupTicks > 60) throw new IllegalArgumentException("Unreviewed held GPU fixture");
        pending = new Request(request, model, action, view, warmupTicks, soleMaid(model));
    }

    public static List<String> actions() {
        return ClientDevelopmentRequest.heldGpuActions();
    }

    public static void tickEnd() {
        clientTickEndSequence++;
        if (pending == null || pending.report != null) return;
        var request = pending;
        if (request.postTerminalTickPending) {
            request.observePostTerminalTick();
            return;
        }
        if (request.installing || request.finishing || request.terminalPrepared) return;
        if (request.cancelReason != null) {
            cancel(request.cancelReason);
            return;
        }
        if (++request.ticks > 200 || System.nanoTime() > request.deadline) {
            cancel("Held GPU frame deadline exceeded");
            return;
        }
        try {
            request.requireContext();
            if (!request.fixtureInstalled && request.warmupTicks > 0) request.installFixture();
            else if (request.fixtureInstalled) request.observeTick();
        } catch (RuntimeException | LinkageError failure) {
            cancel("Held GPU warmup tick failed: " + failure);
        }
    }

    public static void onWorldUnload(World world) {
        if (pending != null && pending.world == world) cancel("Held GPU original client world unloaded");
    }

    public static void renderEnd(float partialTick) {
        if (pending == null || pending.report != null || active != null) return;
        var request = pending;
        if (request.installing || request.finishing || request.terminalPrepared) return;
        if (request.cancelReason != null) {
            cancel(request.cancelReason);
            return;
        }
        if (System.nanoTime() > request.deadline) {
            cancel("Held GPU render deadline exceeded");
            return;
        }
        try {
            request.requireContext();
            if (!request.fixtureInstalled) {
                if (request.warmupTicks > 0) return;
                request.installFixture();
            }
            request.requireOwnedLease();
            request.observeNaturalCompletion();
            if (!request.ready()) return;
            request.captureStarted = true;
            request.phase = "CAPTURING";
        } catch (RuntimeException | LinkageError failure) {
            cancel("Held GPU warmup render failed: " + failure);
            return;
        }
        var report = request.evidence;
        HeldItemGpuState state = null;
        Framebuffer target = null, body = null, empty = null;
        Transaction transaction = null;
        boolean stateRestored = false;
        try {
            requireObserver();
            if (soleMaid(request.model) != request.maid) throw new IllegalStateException("Held GPU actor changed");
            Object pack = Class.forName("net.coderbot.iris.Iris")
                .getMethod("getCurrentPack")
                .invoke(null);
            if (!(pack instanceof Optional<?>) || ((Optional<?>) pack).isPresent())
                throw new IllegalStateException("Held GPU pilot requires no shader pack");
            if (!OpenGlHelper.isFramebufferEnabled() || !GLContext.getCapabilities().OpenGL30)
                throw new IllegalStateException("Reviewed GL3 FBO/query path unavailable");
            var found = RenderManager.instance.getEntityRenderObject(request.maid);
            if (!(found instanceof MaidRenderer renderer))
                throw new IllegalStateException("Actual MaidRenderer missing");
            report.add(
                "runtimeIdentity",
                DevelopmentArtifactIdentity.capture(
                    HeldItemGpuProbe.class,
                    HeldItemGpuState.class,
                    MaidRenderer.class,
                    MaidHeldItemLayer.class));
            report.addProperty("glVendor", GL11.glGetString(GL11.GL_VENDOR));
            report.addProperty("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
            report.addProperty("glVersion", GL11.glGetString(GL11.GL_VERSION));
            report.addProperty(
                "nativeItemRendererClass",
                RenderManager.instance.itemRenderer.getClass()
                    .getName());
            request.requireOwnedLease();
            state = new HeldItemGpuState();
            state.push();
            try (var matrices = renderer.openDevelopmentItemStateScope()) {
                try {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                    target = new Framebuffer(SIZE, SIZE, true);
                    body = new Framebuffer(SIZE, SIZE, true);
                    empty = new Framebuffer(SIZE, SIZE, true);
                    target.bindFramebuffer(true);
                    target.checkFramebufferComplete();
                    configure(request.view);
                    copy(target, empty);
                    copy(empty, target);
                    transaction = new Transaction(request, report, target, body, empty);
                    active = transaction;
                    renderer.doRender(request.maid, 0, 0, 0, 0, partialTick);
                    active = null;
                    if (transaction.seams != 1 || transaction.samples.size() != 10 || report.has("error"))
                        throw new IllegalStateException("Actual equipped-items seam did not complete exactly once");
                } finally {
                    // GL_ALL_ATTRIB包含draw/read buffer，须先回到caller FBO再让production scope弹属性
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.drawFbo);
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, state.readFbo);
                }
            }
        } catch (Exception | LinkageError failure) {
            // RendererLivingEntity会记录并吞下接缝异常，保留先前记录的原始原因
            report.addProperty(report.has("error") ? "renderFailure" : "error", failure.toString());
        } finally {
            active = null;
            if (transaction != null) try {
                transaction.cleanup();
            } catch (RuntimeException | LinkageError failure) {
                if (!report.has("cleanupError")) report.addProperty("cleanupError", failure.toString());
            }
            try {
                if (empty != null) empty.deleteFramebuffer();
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("emptyDeleteError", failure.toString());
            }
            try {
                if (body != null) body.deleteFramebuffer();
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("bodyDeleteError", failure.toString());
            }
            try {
                if (target != null) target.deleteFramebuffer();
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("targetDeleteError", failure.toString());
            }
            if (state != null) try {
                state.restore();
                stateRestored = state.matchesCurrent();
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("stateRestoreError", failure.toString());
            }
        }
        report.addProperty("glStateRestored", stateRestored);
        request.finish(
            transaction != null && transaction.seams == 1
                && transaction.samples.size() == 10
                && !report.has("error")
                && !report.has("cleanupError")
                && !report.has("equipmentRestoreError")
                && !report.has("stateRestoreError")
                && !report.has("bodyDeleteError")
                && !report.has("emptyDeleteError")
                && !report.has("targetDeleteError")
                && stateRestored);
    }

    /** 实际RendererLivingEntity已完成本体，此接缝内不重新准备pose */
    public static boolean atEquippedItems(EntityMaid maid, BuiltinMaidModel model, float partialTick, Runnable draw) {
        var transaction = active;
        if (transaction == null || transaction.request.maid != maid) return false;
        if (!Boolean.getBoolean("tlmnh.devCommands") || transaction.owner != Thread.currentThread())
            throw new IllegalStateException("Unowned held GPU seam");
        transaction.seams++;
        try {
            transaction.capture(model, partialTick, draw);
        } catch (Exception | LinkageError failure) {
            transaction.report.addProperty("error", failure.toString());
            throw new IllegalStateException("Held GPU seam failed", failure);
        }
        return true;
    }

    public static void beforeMaidBody(EntityMaid maid, BuiltinMaidModel model) {
        var transaction = active;
        if (transaction == null) {
            var request = pending;
            if (request != null && request.report == null
                && request.fixtureInstalled
                && request.warmupTicks > 0
                && !request.captureStarted
                && !request.installing
                && !request.finishing
                && !request.terminalPrepared
                && request.phase.equals("WARMING")
                && request.maid == maid) {
                try {
                    request.noteNaturalEntry(model);
                } catch (RuntimeException | LinkageError failure) {
                    cancel("Held GPU natural body observation failed: " + failure);
                }
            }
            return;
        }
        if (transaction.request.maid != maid) return;
        transaction.request.requireCaptureModel(model);
        if (++transaction.rendererEntries != 1) throw new IllegalStateException("Nested actual maid renderer in pilot");
        transaction.poseBefore = model.getPoseSerial();
    }

    public static void cancel(String reason) {
        if (pending != null && pending.report == null) {
            var request = pending;
            if (request.cancelReason == null)
                request.cancelReason = reason == null ? "Held GPU cancellation requested" : reason;
            if (Thread.currentThread() != request.owner || active != null || request.installing || request.finishing)
                return;
            request.finish(false);
        }
    }

    public static Result poll() {
        if (pending == null || pending.report == null
            || Thread.currentThread() != pending.owner
            || pending.installing
            || pending.finishing
            || pending.postTerminalTickPending) return null;
        var result = new Result(pending.request, pending.report);
        pending = null;
        return result;
    }

    private static JsonObject header(Request request) {
        var report = new JsonObject();
        report.addProperty("schema", "held-item-gpu-pilot-v3");
        report.addProperty("status", "complete");
        report.addProperty("transaction", request.request);
        report.addProperty("model", request.model);
        report.addProperty("action", request.action);
        report.addProperty("side", request.side.name());
        report.addProperty("view", request.view);
        var camera = new JsonObject();
        camera.add("eye", GSON.toJsonTree(cameraEye(request.view)));
        camera.add("target", GSON.toJsonTree(new float[] { 0, 1, 0 }));
        camera.add("up", GSON.toJsonTree(new float[] { 0, 1, 0 }));
        report.add("camera", camera);
        report.addProperty("crossViewSamePose", false);
        report.addProperty("itemOnlyAppearanceAcceptance", false);
        report.addProperty("batchObservationSchema", "angelica-immediate-query-observation-v1");
        report.addProperty(
            "batchObservationScope",
            "actual active/hasQueuedGeometry/hasHeldEntities and cumulative counters; raw queue sizes unknown");
        report.addProperty(
            "entityUuid",
            request.maid.getUniqueID()
                .toString());
        report.addProperty("width", SIZE);
        report.addProperty("height", SIZE);
        report.addProperty("depthEncoding", "GL_DEPTH_COMPONENT_FLOAT32_" + java.nio.ByteOrder.nativeOrder());
        report.addProperty(
            "scope",
            "one actual MaidRenderer body per view; same-pose body and empty-baseline item-layer replay in no-shader FBO; item-only is diagnostic");
        report.addProperty("worldNaturalVerified", false);
        report.addProperty("sourceG1Verified", false);
        report.addProperty("all35Verified", false);
        report.addProperty("invisibleVerified", false);
        report.addProperty("cacheHitDirectlyObserved", false);
        report.addProperty("shaderPackActive", false);
        report.addProperty("temporaryClientEquipmentFixture", true);
        report.addProperty("animationStateRewound", false);
        report.addProperty("transformedDefinitionBytesVerified", false);
        return report;
    }

    private static ItemStack item(String action, JsonObject report) throws Exception {
        if (action.startsWith("shield_")) {
            var construction = new JsonObject();
            var shields = MaidShieldFixtures.createWoodShields(construction);
            report.add("construction", construction);
            String name = action.substring(7);
            String id = name.equals("round") ? "tinkersdefense:Round Shield"
                : name.equals("heater") ? "tinkersdefense:Heater Shield" : "battlegear2:shield." + name;
            return shields.get(id)
                .copy();
        }
        return switch (action) {
            case "main_icon" -> new ItemStack(Items.paper);
            case "main_bow" -> new ItemStack(Items.bow);
            case "main_block" -> new ItemStack(Blocks.stone);
            case "main_tool" -> new ItemStack(Items.iron_sword);
            default -> throw new IllegalArgumentException("Unknown held fixture");
        };
    }

    private static float[] cameraEye(String view) {
        return switch (view) {
            case "positive_z" -> new float[] { 0, 1, 5 };
            case "negative_z" -> new float[] { 0, 1, -5 };
            case "positive_x" -> new float[] { 5, 1, 0 };
            case "negative_x" -> new float[] { -5, 1, 0 };
            default -> throw new IllegalArgumentException("Unknown held GPU view");
        };
    }

    private static void configure(String view) {
        GL20.glUseProgram(0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(1);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        for (int cap : new int[] { GL11.GL_FOG, GL11.GL_BLEND, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST,
            GL13.GL_MULTISAMPLE, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE }) GL11.glDisable(cap);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, .1F);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(-2.5, 2.5, -1.4, 1.4, -10, 10);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        float[] eye = cameraEye(view);
        var matrix = new Matrix4f().lookAt(eye[0], eye[1], eye[2], 0, 1, 0, 0, 1, 0);
        var buffer = BufferUtils.createFloatBuffer(16);
        matrix.get(buffer);
        GL11.glLoadMatrix(buffer);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static EntityMaid soleMaid(String model) {
        var mc = Minecraft.getMinecraft();
        EntityMaid selected = null;
        for (Object entry : mc.theWorld.loadedEntityList) if (entry instanceof EntityMaid maid) {
            if (selected != null) throw new IllegalStateException("Held GPU fixture requires one actual maid");
            selected = maid;
        }
        if (selected == null || !selected.isEntityAlive()
            || selected.isInvisible()
            || !selected.isOwner(mc.thePlayer)
            || !selected.getModelId()
                .equals(model)
            || selected.ridingEntity != null
            || selected.riddenByEntity != null
            || selected.getLeashed()
            || selected.getDistanceSqToEntity(mc.thePlayer) > 64
            || mc.theWorld.getEntityByID(selected.getEntityId()) != selected)
            throw new IllegalStateException("Sole visible owned registered lab maid required");
        return selected;
    }

    private static void requireObserver() {
        var mc = Minecraft.getMinecraft();
        var player = mc.thePlayer;
        if (!Boolean.getBoolean("tlmnh.devCommands") || !AnimationLab.isLab(mc.theWorld)
            || player == null
            || mc.currentScreen != null
            || mc.playerController == null
            || !player.isEntityAlive()
            || !player.capabilities.disableDamage
            || !player.capabilities.allowFlying
            || !player.capabilities.isFlying
            || !player.noClip
            || player.capabilities.isCreativeMode)
            throw new IllegalStateException("Unobscured safe lab spectator required");
        GameType mode = ReflectionHelper
            .getPrivateValue(PlayerControllerMP.class, mc.playerController, "currentGameType", "field_78779_k");
        if (mode == null || mode.getID() != 3) throw new IllegalStateException("Actual spectator mode 3 required");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hex(byte[] bytes) {
        var result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static String hash(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    private static String resourceHash(ResourceLocation resource) throws Exception {
        try (InputStream input = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(resource)
            .getInputStream()) {
            var digest = digest();
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) >= 0) digest.update(bytes, 0, count);
            return hex(digest.digest());
        }
    }

    private static byte[] rgba() {
        var buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        return bytes;
    }

    private static void awaitQuery(int query, long requestDeadline, JsonObject wait) {
        long started = System.nanoTime();
        long budget = Math.min(500_000_000L, requestDeadline - started);
        wait.addProperty("maximumWaitNanos", 500_000_000L);
        wait.addProperty("budgetNanos", Math.max(0, budget));
        wait.addProperty("polls", 0);
        wait.addProperty("timedOut", false);
        int polls = 0;
        while (true) {
            long elapsed = System.nanoTime() - started;
            wait.addProperty("elapsedNanos", elapsed);
            if (elapsed >= budget || Thread.currentThread()
                .isInterrupted()) {
                wait.addProperty("timedOut", true);
                throw new IllegalStateException("Native query availability deadline exceeded");
            }
            int available = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE);
            if (++polls == 1) wait.addProperty("firstAvailable", available);
            wait.addProperty("polls", polls);
            wait.addProperty("lastAvailable", available);
            elapsed = System.nanoTime() - started;
            wait.addProperty("elapsedNanos", elapsed);
            if (available != 0 && available != 1)
                throw new IllegalStateException("Invalid native query availability value");
            if (elapsed >= budget) {
                wait.addProperty("timedOut", true);
                throw new IllegalStateException("Native query availability deadline exceeded");
            }
            if (available == 1) return;
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    }

    private static float[] matrix(int name) {
        var buffer = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(name, buffer);
        float[] values = new float[16];
        buffer.get(values);
        return values;
    }

    private static JsonObject rasterState() {
        var state = new JsonObject();
        state.addProperty("depthTest", GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        state.addProperty("depthFunction", GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        state.addProperty("depthWrite", GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK));
        state.addProperty("alphaTest", GL11.glIsEnabled(GL11.GL_ALPHA_TEST));
        state.addProperty("alphaFunction", GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC));
        state.addProperty("alphaReference", GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
        state.addProperty("cullFace", GL11.glIsEnabled(GL11.GL_CULL_FACE));
        state.addProperty("cullMode", GL11.glGetInteger(GL11.GL_CULL_FACE_MODE));
        state.addProperty("frontFace", GL11.glGetInteger(GL11.GL_FRONT_FACE));
        return state;
    }

    private static byte[] depth() {
        var buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        return bytes;
    }

    private static void requireEmptyBaseline(byte[] color, byte[] depth) {
        for (byte value : color)
            if (value != 0) throw new IllegalStateException("Diagnostic empty baseline contains actual color");
        var values = java.nio.ByteBuffer.wrap(depth)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer();
        while (values.hasRemaining())
            if (values.get() != 1F) throw new IllegalStateException("Diagnostic empty baseline contains actual depth");
    }

    private static void copy(Framebuffer from, Framebuffer to) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.framebufferObject);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.framebufferObject);
        GL30.glBlitFramebuffer(
            0,
            0,
            SIZE,
            SIZE,
            0,
            0,
            SIZE,
            SIZE,
            GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
            GL11.GL_NEAREST);
        to.bindFramebuffer(true);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    /** 只读实际链骨，不将不存在的hidden/scale字段填成推测的默认值 */
    private static JsonObject boneStates(BuiltinMaidModel model, MaidHeldItemLayer.HandSnapshot hand) {
        var result = new JsonObject();
        result.addProperty("backend", hand.attachment.backend);
        result.add("attachmentChain", GSON.toJsonTree(hand.attachment.boneChain));
        var bones = new JsonArray();
        result.add("bones", bones);
        if (model.getBedrock() != null) {
            var parts = model.getBedrock()
                .getModelMap();
            String arm = hand.attachment.side == Side.LEFT ? "armLeft" : "armRight";
            for (String name : new String[] { arm, arm + "PositioningBone" }) {
                var row = new JsonObject();
                var part = parts.get(name);
                row.addProperty("name", name);
                row.addProperty("present", part != null);
                row.addProperty("includedInAttachmentChain", hand.attachment.boneChain.contains(name));
                if (part != null) {
                    row.add("scale", GSON.toJsonTree(new float[] { part.xScale, part.yScale, part.zScale }));
                    row.addProperty("visible", part.visible);
                    row.add("position", GSON.toJsonTree(new float[] { part.x, part.y, part.z }));
                    row.add("rotation", GSON.toJsonTree(new float[] { part.xRot, part.yRot, part.zRot }));
                }
                bones.add(row);
            }
        } else {
            var chain = hand.attachment.side == Side.LEFT ? model.getGecko()
                .leftHandBones()
                : model.getGecko()
                    .rightHandBones();
            result.addProperty("locatorChainSize", chain.size());
            for (var bone : chain) {
                var row = new JsonObject();
                row.addProperty("name", bone.getName());
                row.addProperty("present", true);
                row.add("scale", GSON.toJsonTree(new float[] { bone.getScaleX(), bone.getScaleY(), bone.getScaleZ() }));
                row.addProperty("hidden", bone.isHidden());
                row.addProperty("cubesHidden", bone.cubesAreHidden());
                row.addProperty("childBonesHidden", bone.childBonesAreHiddenToo());
                row.add(
                    "position",
                    GSON.toJsonTree(new float[] { bone.getPositionX(), bone.getPositionY(), bone.getPositionZ() }));
                row.add(
                    "rotation",
                    GSON.toJsonTree(new float[] { bone.getRotationX(), bone.getRotationY(), bone.getRotationZ() }));
                bones.add(row);
            }
        }
        return result;
    }

    /** actual RC2 2.2.28只读查询，绝不flush/suspend或切换全局Java batch队列 */
    private static final class BatchReader {

        final Class<?> batcherType, instancerType, glsmType;
        final Object batcher;
        final Method active, queued, held;
        final Method[] batchCounters, itemCounters;
        final Field drawCalls;

        BatchReader() throws Exception {
            batcherType = Class.forName("com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher");
            instancerType = Class.forName("com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer");
            glsmType = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
            batcher = batcherType.getField("INSTANCE")
                .get(null);
            active = batcherType.getMethod("isActive");
            queued = batcherType.getDeclaredMethod("hasQueuedGeometry");
            held = batcherType.getDeclaredMethod("hasHeldEntities");
            queued.setAccessible(true);
            held.setAccessible(true);
            batchCounters = new Method[] { batcherType.getMethod("statParts"),
                batcherType.getMethod("statInstancedDraws"), batcherType.getMethod("statInstancedInstances"),
                batcherType.getMethod("statRedrawnInstances") };
            itemCounters = new Method[] { instancerType.getMethod("statInstanced"),
                instancerType.getMethod("statGlintInstanced"), instancerType.getMethod("statFallback") };
            drawCalls = glsmType.getField("drawCalls");
        }

        JsonObject snapshot() {
            var state = new JsonObject();
            state.addProperty("rawQueueSizesKnown", false);
            try {
                state.addProperty("active", (Boolean) active.invoke(batcher));
                state.addProperty("queuedGeometry", (Boolean) queued.invoke(batcher));
                state.addProperty("heldEntities", (Boolean) held.invoke(batcher));
                var batch = new JsonObject();
                for (Method method : batchCounters) batch.addProperty(method.getName(), (Long) method.invoke(batcher));
                state.add("batcherCounters", batch);
                var items = new JsonObject();
                for (Method method : itemCounters) items.addProperty(method.getName(), (Long) method.invoke(null));
                state.add("itemInstancerCounters", items);
                state.addProperty("glsmDrawCalls", drawCalls.getLong(null));
                state.addProperty("known", true);
            } catch (Exception | LinkageError failure) {
                state.addProperty("known", false);
                state.addProperty("readError", failure.toString());
            }
            return state;
        }
    }

    private static void requireImmediateBatch(JsonObject state) {
        if (!state.get("known")
            .getAsBoolean() || state.get("active")
                .getAsBoolean()
            || state.get("queuedGeometry")
                .getAsBoolean()
            || state.get("heldEntities")
                .getAsBoolean())
            throw new IllegalStateException(
                "Held GPU query contract requires known inactive non-pending Java batching");
    }

    private static final class Geometry implements LegacyVertexConsumer {

        private final MessageDigest digest = digest();
        private int vertices;

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
            int overlay, int light, float nx, float ny, float nz) {
            if (++vertices > 65536) throw new IllegalStateException("Held GPU body exceeds vertex bound");
            for (float value : new float[] { x, y, z, red, green, blue, alpha, u, v, nx, ny, nz }) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite actual body geometry");
                integer(Float.floatToIntBits(value == 0 ? 0 : value));
            }
            integer(overlay);
            integer(light);
        }

        private void integer(int value) {
            digest.update((byte) (value >>> 24));
            digest.update((byte) (value >>> 16));
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
        }

        private String finish() {
            if (vertices == 0) throw new IllegalStateException("Missing actual body geometry");
            return hex(digest.digest());
        }
    }

    private static final class Transaction implements MaidHeldItemLayer.Observer {

        final Request request;
        final JsonObject report;
        final Framebuffer target, body, empty;
        final Thread owner = Thread.currentThread();
        final List<byte[]> samples = new ArrayList<>();
        final JsonArray sampleReports = new JsonArray();
        final JsonArray itemOnlyReports = new JsonArray();
        final JsonArray queryWaits = new JsonArray();
        final JsonArray batchObservations = new JsonArray();
        final Path game, folder;
        final BatchReader batches;
        MaidHeldItemLayer.DevelopmentDrawGate gate;
        JsonArray calls, hands;
        JsonObject nativeBatchBefore;
        BuiltinMaidModel observedModel;
        String phase;
        int seams, rendererEntries, entered, completed, skipped;
        long poseBefore;
        boolean observing;

        Transaction(Request request, JsonObject report, Framebuffer target, Framebuffer body, Framebuffer empty)
            throws Exception {
            this.request = request;
            this.report = report;
            this.target = target;
            this.body = body;
            this.empty = empty;
            try {
                batches = new BatchReader();
            } catch (Exception | LinkageError failure) {
                var unknown = new JsonObject();
                unknown.addProperty("known", false);
                unknown.addProperty("readError", failure.toString());
                report.add("batchingInitialization", unknown);
                throw failure;
            }
            report.add(
                "batchingIdentity",
                DevelopmentArtifactIdentity.capture(batches.batcherType, batches.instancerType, batches.glsmType));
            report.add("batchObservations", batchObservations);
            var initialBatch = batchState("transaction_begin", 0);
            report.add("batchingInitialization", initialBatch);
            requireImmediateBatch(initialBatch);
            game = Minecraft.getMinecraft().mcDataDir.toPath()
                .toRealPath();
            Path directory = game;
            String[] parts = { "tlmnh-dev", "held-item-gpu", request.request.replace(".json", "") };
            for (int i = 0; i < parts.length; i++) {
                directory = directory.resolve(parts[i]);
                if (i == parts.length - 1 && Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("Held GPU artifact directory already exists");
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files
                        .readAttributes(
                            directory,
                            java.nio.file.attribute.DosFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS)
                        .isOther()
                    || !directory.toRealPath()
                        .startsWith(game))
                    throw new IllegalStateException("Held GPU directory escaped runtime");
            }
            folder = directory;
            report.add("samples", sampleReports);
            report.add("itemOnlySamples", itemOnlyReports);
            report.add("queryWaits", queryWaits);
        }

        JsonObject batchState(String boundary, long nativeSequence) {
            var state = batches.snapshot();
            state.addProperty("boundary", boundary);
            state.addProperty("phase", phase);
            state.addProperty("nativeSequence", nativeSequence);
            batchObservations.add(state);
            return state;
        }

        void capture(BuiltinMaidModel model, float partialTick, Runnable draw) throws Exception {
            request.requireOwnedLease();
            observedModel = model;
            if (seams != 1 || !model.hasCompletedPoseFor(request.maid)
                || !model.getChoice()
                    .getId()
                    .equals(request.model))
                throw new IllegalStateException("Held GPU missing actual completed body pose");
            long serial = model.getPoseSerial();
            if (rendererEntries != 1 || serial - poseBefore != 1)
                throw new IllegalStateException("Actual body pose was not prepared exactly once");
            int tick = request.maid.ticksExisted;
            int light = request.maid.getBrightnessForRender(partialTick);
            report.addProperty("actorTick", tick);
            report.addProperty("partialTick", partialTick);
            report.addProperty("poseSerial", serial);
            report.addProperty("poseSerialBefore", poseBefore);
            report.addProperty("rendererEntries", rendererEntries);
            float[] bodyView = matrix(GL11.GL_MODELVIEW_MATRIX), projection = matrix(GL11.GL_PROJECTION_MATRIX);
            var raster = rasterState();
            if (!raster.get("depthTest")
                .getAsBoolean()
                || !raster.get("alphaTest")
                    .getAsBoolean())
                throw new IllegalStateException("Held GPU requires actual normal depth and alpha tests");
            report.add("rasterState", raster);
            report.add("bodyModelView", GSON.toJsonTree(bodyView));
            report.add("projection", GSON.toJsonTree(projection));
            report.addProperty(
                "geometryResourceSha256",
                resourceHash(
                    model.getChoice()
                        .getGeometry()));
            report.addProperty(
                "textureResourceSha256",
                resourceHash(
                    model.getChoice()
                        .getTexture()));
            var initial = new Geometry();
            model.renderGeometry(new LegacyPoseStack(), initial, light);
            String geometry = initial.finish();
            report.addProperty("bodyLocalGeometrySha256", geometry);
            report.addProperty("bodyVertices", initial.vertices);
            copy(target, body);
            byte[] bodyColor = rgba(), bodyDepth = depth();
            var baseline = save("body", bodyColor, bodyDepth);
            report.add("bodyBaseline", baseline);
            if (baseline.get("occupiedPixels")
                .getAsInt() < 64) throw new IllegalStateException("Actual body raster is empty or insufficient");
            copy(empty, target);
            byte[] emptyColor = rgba(), emptyDepth = depth();
            report.add("emptyBaseline", save("empty", emptyColor, emptyDepth));
            requireEmptyBaseline(emptyColor, emptyDepth);
            gate = MaidHeldItemLayer.installDevelopmentDrawGate(
                request.maid.getUniqueID()
                    .toString(),
                request.side,
                request.request);
            MaidHeldItemLayer.setObserverForDevelopment(this);
            observing = true;
            String[] phases = { "normal0", "normal1", "normal2", "skip_native_draw", "recovery" };
            for (int index = 0; index < phases.length * 2; index++) {
                request.requireOwnedLease();
                if (System.nanoTime() > request.deadline)
                    throw new IllegalStateException("Held GPU transaction deadline");
                boolean itemOnly = index >= phases.length;
                phase = (itemOnly ? "item_only_" : "") + phases[index % phases.length];
                byte[] baselineColor = itemOnly ? emptyColor : bodyColor,
                    baselineDepth = itemOnly ? emptyDepth : bodyDepth;
                copy(itemOnly ? empty : body, target);
                byte[] beforeColor = rgba(), beforeDepth = depth();
                if (!Arrays.equals(baselineColor, beforeColor) || !Arrays.equals(baselineDepth, beforeDepth))
                    throw new IllegalStateException("Actual color/depth baseline was not restored exactly");
                float[] entryView = matrix(GL11.GL_MODELVIEW_MATRIX),
                    entryProjection = matrix(GL11.GL_PROJECTION_MATRIX);
                if (!Arrays.equals(bodyView, entryView) || !Arrays.equals(projection, entryProjection))
                    throw new IllegalStateException("Body GL matrix changed before equipment draw");
                var entryRaster = rasterState();
                if (!raster.equals(entryRaster))
                    throw new IllegalStateException("Raster state changed before equipment draw");
                var beforeGeometry = new Geometry();
                model.renderGeometry(new LegacyPoseStack(), beforeGeometry, light);
                String beforeHash = beforeGeometry.finish();
                if (!geometry.equals(beforeHash) || request.maid.ticksExisted != tick
                    || model.getPoseSerial() != serial)
                    throw new IllegalStateException("Body pose changed before equipment draw");
                boolean skip = index % phases.length == 3;
                MaidHeldItemLayer.setDevelopmentDrawSkip(gate, skip);
                calls = new JsonArray();
                hands = new JsonArray();
                entered = completed = skipped = 0;
                var phaseBatchBefore = batchState("phase_before", 0);
                requireImmediateBatch(phaseBatchBefore);
                int query = GL15.glGenQueries();
                if (query == 0) throw new IllegalStateException("Missing native occlusion query");
                boolean queryActive = false;
                long gpuSamples;
                try {
                    if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0)
                        throw new IllegalStateException("Caller occlusion query already active");
                    GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
                    queryActive = true;
                    draw.run();
                    request.requireOwnedLease();
                    var phaseBatchAfter = batchState("phase_after", 0);
                    requireImmediateBatch(phaseBatchAfter);
                    if (hands.size() != 2)
                        throw new IllegalStateException("Missing actual left/right hand observations");
                    GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                    queryActive = false;
                    var wait = new JsonObject();
                    wait.addProperty("phase", phase);
                    wait.addProperty("queryId", query);
                    wait.addProperty("flushCalled", false);
                    queryWaits.add(wait);
                    GL11.glFlush();
                    wait.addProperty("flushCalled", true);
                    byte[] color = rgba(), afterDepth = depth();
                    // 像素回读不保证query已就绪，只在真实AVAILABLE后读取可能阻塞的RESULT
                    awaitQuery(query, request.deadline, wait);
                    // RC2 Angelica提供scalar i，scalar ui会被重定向到不存在的重载
                    int result = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
                    if (result < 0) throw new IllegalStateException("Native query exceeds signed result range");
                    gpuSamples = result;
                    var item = save(phase, color, afterDepth);
                    item.add("queryWait", wait);
                    item.addProperty("baselinePhase", itemOnly ? "empty" : "body");
                    item.addProperty("baselineBeforeColorSha256", hash(beforeColor));
                    item.addProperty("baselineBeforeDepthSha256", hash(beforeDepth));
                    if (!itemOnly) {
                        item.addProperty("bodyBeforeColorSha256", hash(beforeColor));
                        item.addProperty("bodyBeforeDepthSha256", hash(beforeDepth));
                    }
                    item.addProperty("bodyBeforeGeometrySha256", beforeHash);
                    item.add("entryModelView", GSON.toJsonTree(entryView));
                    item.add("entryProjection", GSON.toJsonTree(entryProjection));
                    item.add("entryRasterState", entryRaster);
                    item.addProperty("occlusionSamples", gpuSamples);
                    item.addProperty("nativeEntered", entered);
                    item.addProperty("nativeReturned", completed);
                    item.addProperty("nativeSkipped", skipped);
                    item.add("nativeCalls", calls);
                    item.add("hands", hands);
                    item.add("batchBefore", phaseBatchBefore);
                    item.add("batchAfter", phaseBatchAfter);
                    var afterGeometry = new Geometry();
                    model.renderGeometry(new LegacyPoseStack(), afterGeometry, light);
                    String afterHash = afterGeometry.finish();
                    float[] exitView = matrix(GL11.GL_MODELVIEW_MATRIX),
                        exitProjection = matrix(GL11.GL_PROJECTION_MATRIX);
                    item.add("exitModelView", GSON.toJsonTree(exitView));
                    item.add("exitProjection", GSON.toJsonTree(exitProjection));
                    item.addProperty("bodyAfterGeometrySha256", afterHash);
                    var exitRaster = rasterState();
                    item.add("exitRasterState", exitRaster);
                    if (!geometry.equals(afterHash) || request.maid.ticksExisted != tick
                        || model.getPoseSerial() != serial
                        || !Arrays.equals(bodyView, exitView)
                        || !Arrays.equals(projection, exitProjection)
                        || !raster.equals(exitRaster))
                        throw new IllegalStateException("Body pose changed during equipment draw");
                    samples.add(color);
                    (itemOnly ? itemOnlyReports : sampleReports).add(item);
                } finally {
                    try {
                        if (queryActive) GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                    } finally {
                        GL15.glDeleteQueries(query);
                    }
                }
            }
            report.addProperty("bodyPreparedOnce", true);
            report.addProperty("bodySeamCalls", seams);
            report.addProperty("gateSuppressedCalls", gate.getSuppressedCalls());
            report.addProperty(
                "normalRepetitionsEqual",
                Arrays.equals(samples.get(0), samples.get(1)) && Arrays.equals(samples.get(1), samples.get(2)));
            report.addProperty("recoveryPixelsEqual", Arrays.equals(samples.get(2), samples.get(4)));
            report.addProperty(
                "itemOnlyNormalRepetitionsEqual",
                Arrays.equals(samples.get(5), samples.get(6)) && Arrays.equals(samples.get(6), samples.get(7)));
            report.addProperty("itemOnlyRecoveryPixelsEqual", Arrays.equals(samples.get(7), samples.get(9)));
        }

        @Override
        public void hand(MaidHeldItemLayer.HandSnapshot hand) {
            if (observedModel == null || hands == null
                || !hand.actor.equals(
                    request.maid.getUniqueID()
                        .toString())
                || !hand.actualModel.equals(request.model)) throw new IllegalStateException("Unowned hand observation");
            var row = GSON.toJsonTree(hand)
                .getAsJsonObject();
            row.add("boneStates", boneStates(observedModel, hand));
            hands.add(row);
        }

        @Override
        public void beforeNativeCall(MaidHeldItemLayer.NativeCall call) {
            nativeBatchBefore = batchState("native_before", call.sequence);
            requireImmediateBatch(nativeBatchBefore);
            if (!call.hand.actor.equals(
                request.maid.getUniqueID()
                    .toString())
                || call.hand.attachment.side != request.side)
                throw new IllegalStateException("Unowned native item call entered query");
            if (!call.selectedRendererClass.isEmpty() && !report.has("customRendererIdentity")) {
                try {
                    report.add(
                        "customRendererIdentity",
                        DevelopmentArtifactIdentity.capture(Class.forName(call.selectedRendererClass)));
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot bind actual custom item renderer", failure);
                }
            }
        }

        @Override
        public void afterNativeCall(MaidHeldItemLayer.NativeCall call, boolean wasEntered, boolean returned,
            String failure) {
            var row = GSON.toJsonTree(call)
                .getAsJsonObject();
            row.addProperty("entered", wasEntered);
            row.addProperty("completed", returned);
            row.addProperty("failure", failure);
            var after = batchState("native_after", call.sequence);
            row.add("batchBefore", nativeBatchBefore);
            row.add("batchAfter", after);
            calls.add(row);
            if (wasEntered) entered++;
            if (returned) completed++;
            requireImmediateBatch(after);
        }

        @Override
        public void skippedByDevelopmentGate(MaidHeldItemLayer.NativeCall call, String transaction) {
            if (!request.request.equals(transaction)) throw new IllegalStateException("Wrong native skip transaction");
            skipped++;
        }

        void cleanup() {
            boolean observerRemoved = !observing || MaidHeldItemLayer.clearObserverForDevelopment(this);
            boolean gateRemoved = gate == null || MaidHeldItemLayer.clearDevelopmentDrawGate(gate);
            report.addProperty("observerRemoved", observerRemoved);
            report.addProperty("gateRemoved", gateRemoved);
            if (!observerRemoved || !gateRemoved)
                report.addProperty("cleanupError", "Owned held GPU hook was not removed");
        }

        JsonObject save(String name, byte[] color, byte[] depth) throws Exception {
            Path rgba = folder.resolve(name + ".rgba"), depthFile = folder.resolve(name + ".depth32"),
                png = folder.resolve(name + ".png");
            Files.write(rgba, color, StandardOpenOption.CREATE_NEW);
            Files.write(depthFile, depth, StandardOpenOption.CREATE_NEW);
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            int occupied = 0;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int offset = ((SIZE - 1 - y) * SIZE + x) * 4;
                int alpha = color[offset + 3] & 255;
                if (alpha != 0) occupied++;
                image.setRGB(
                    x,
                    y,
                    alpha << 24 | (color[offset] & 255) << 16
                        | (color[offset + 1] & 255) << 8
                        | color[offset + 2] & 255);
            }
            if (!ImageIO.write(image, "png", png.toFile())) throw new IllegalStateException("PNG writer unavailable");
            var result = new JsonObject();
            result.addProperty("phase", name);
            result.addProperty("rgba", relative(rgba));
            result.addProperty("rgbaSha256", hash(color));
            result.addProperty("depth", relative(depthFile));
            result.addProperty("depthSha256", hash(depth));
            result.addProperty("png", relative(png));
            result.addProperty("pngSha256", hash(Files.readAllBytes(png)));
            result.addProperty("occupiedPixels", occupied);
            return result;
        }

        String relative(Path path) {
            return game.relativize(path)
                .toString()
                .replace('\\', '/');
        }
    }

    /** 一次显式开发故障：真实工厂事件中取消本事务，不修改事件或替换工厂 */
    public static final class FactoryCancellationListener {

        private final Request request;
        private final JsonObject observation = new JsonObject();
        private boolean armed, registrationAttempted;

        private FactoryCancellationListener(Request request) {
            this.request = request;
            request.factoryCancellationProbe = observation;
            request.evidence.add("factoryCancellationProbe", observation);
            observation.addProperty("schema", "held-factory-cancellation-v1");
            observation.addProperty("requested", true);
            observation.addProperty("triggerCount", 0);
            observation.addProperty("factoryReturned", false);
            observation.addProperty("registerCallCompleted", false);
            observation.addProperty("unregisterCallCompleted", false);
        }

        private void register() {
            registrationAttempted = true;
            armed = true;
            MinecraftForge.EVENT_BUS.register(this);
            observation.addProperty("registerCallCompleted", true);
        }

        @SubscribeEvent
        public void onNativeFactoryEvent(Event event) {
            if (!armed || factoryCancellationConsumed
                || !Boolean.getBoolean("tlmnh.devCommands")
                || !Boolean.getBoolean("tlmnh.heldCancelFactoryOnce")
                || pending != request
                || Thread.currentThread() != request.owner
                || !request.installing
                || request.report != null
                || !event.getClass()
                    .getName()
                    .equals("tconstruct.library.event.ToolBuildEvent"))
                return;
            factoryCancellationConsumed = true;
            request.factoryFaultTriggered = true;
            observation.addProperty("triggerCount", 1);
            observation.addProperty(
                "eventClass",
                event.getClass()
                    .getName());
            observation.addProperty("request", request.request);
            observation.addProperty("actor", request.actor);
            observation.addProperty("ownerThreadMatched", true);
            observation.addProperty("installingAtCallback", request.installing);
            observation.addProperty("finishingAtCallback", request.finishing);
            observation.addProperty("terminalPublishedAtCallback", request.report != null);
            observation.addProperty("mainWrittenAtCallback", request.mainLease.written);
            observation.addProperty("offWrittenAtCallback", request.offLease.written);
            boolean cancelled = event.isCanceled();
            observation.addProperty("eventCancelledBefore", cancelled);
            try {
                observation.add("eventRuntimeIdentity", DevelopmentArtifactIdentity.capture(event.getClass()));
            } catch (Exception | LinkageError failure) {
                observation.addProperty("eventIdentityError", failure.toString());
            }
            cancel("Development native ToolBuildEvent callback cancelled this held fixture installation");
            observation.addProperty("eventCancelledAfter", event.isCanceled());
            observation.addProperty("terminalPublishedAfterCallback", request.report != null);
        }

        private void close() {
            armed = false;
            if (registrationAttempted) try {
                MinecraftForge.EVENT_BUS.unregister(this);
                observation.addProperty("unregisterCallCompleted", true);
            } catch (RuntimeException | LinkageError failure) {
                observation.addProperty("unregisterError", failure.toString());
                cancel("Held factory cancellation listener cleanup failed");
            }
        }
    }

    private static final class Request {

        final String request, model, action, view;
        final EntityMaid maid;
        final Side side;
        final World world;
        final Object player;
        final String actor;
        final int entityId, warmupTicks;
        final Thread owner = Thread.currentThread();
        final long deadline = System.nanoTime() + 15_000_000_000L;
        final HandLease mainLease, offLease;
        final JsonObject evidence;
        final List<JsonObject> tickSamples = new ArrayList<>(), naturalFrames = new ArrayList<>();
        String phase = "ARMED", failureReason = "";
        volatile String cancelReason;
        boolean fixtureInstalled, captureStarted, readyForCapture, ownershipIntact = true;
        boolean installing, finishing, terminalPrepared, factoryFaultTriggered, postTerminalTickPending;
        long terminalClientTickEndSequence;
        int terminalEntityTick;
        JsonObject factoryCancellationProbe;
        boolean modelReferenceStable = true, playerReferenceStable = true, naturalEntryPending;
        int installEntityTick, clientTickEnds, naturalRendererEntries, completedNaturalBodies;
        long entryPoseSerial;
        BuiltinMaidModel naturalModel;
        BuiltinGeckoPlayer naturalPlayer;
        JsonObject restoration;
        int ticks;
        JsonObject report;

        Request(String request, String model, String action, String view, int warmupTicks, EntityMaid maid) {
            this.request = request;
            this.model = model;
            this.action = action;
            this.view = view;
            this.warmupTicks = warmupTicks;
            this.maid = maid;
            world = maid.worldObj;
            player = Minecraft.getMinecraft().thePlayer;
            actor = maid.getUniqueID()
                .toString();
            entityId = maid.getEntityId();
            side = action.startsWith("shield_") ? Side.LEFT : Side.RIGHT;
            mainLease = new HandLease(maid, false);
            offLease = new HandLease(maid, true);
            evidence = header(this);
            evidence.add("originalMainhand", snapshot(mainLease.originalValue));
            evidence.add("originalOffhand", snapshot(offLease.originalValue));
        }

        void requireContext() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Held GPU requires original client thread");
            var mc = Minecraft.getMinecraft();
            if (mc.theWorld != world || mc.thePlayer != player
                || maid.worldObj != world
                || !actor.equals(
                    maid.getUniqueID()
                        .toString())
                || entityId != maid.getEntityId())
                throw new IllegalStateException("Held GPU original world/actor/owner context changed");
            requireObserver();
            if (soleMaid(model) != maid) throw new IllegalStateException("Held GPU registered actor/model changed");
            if (cancelReason != null) throw new IllegalStateException(cancelReason);
        }

        void installFixture() {
            if (installing || finishing || terminalPrepared || report != null)
                throw new IllegalStateException("Held GPU installer is no longer live");
            installing = true;
            FactoryCancellationListener cancellation = null;
            try {
                requireInstallationCheckpoint();
                evidence.add(
                    "runtimeIdentity",
                    DevelopmentArtifactIdentity.capture(
                        HeldItemGpuProbe.class,
                        HeldItemGpuState.class,
                        MaidRenderer.class,
                        MaidHeldItemLayer.class));
                if (Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.heldCancelFactoryOnce")
                    && !factoryCancellationConsumed
                    && action.startsWith("shield_")) {
                    cancellation = new FactoryCancellationListener(this);
                    cancellation.register();
                }
                var fixture = item(action, evidence);
                if (factoryCancellationProbe != null) factoryCancellationProbe.addProperty("factoryReturned", true);
                requireInstallationCheckpoint();
                mainLease.write(side == Side.RIGHT ? fixture : null, this);
                requireInstallationCheckpoint();
                offLease.write(side == Side.LEFT ? fixture : null, this);
                requireInstallationCheckpoint();
                fixtureInstalled = true;
                installEntityTick = maid.ticksExisted;
                phase = "WARMING";
                recordTick();
                evidence.add("actualMainhand", snapshot(mainLease.ownedValue));
                evidence.add("actualOffhand", snapshot(offLease.ownedValue));
                requireOwnedLease();
            } catch (Exception | LinkageError failure) {
                throw new IllegalStateException("Held GPU fixture installation failed: " + failure, failure);
            } finally {
                try {
                    if (cancellation != null) cancellation.close();
                } finally {
                    installing = false;
                    if (cancelReason != null && report == null) finish(false);
                }
            }
        }

        void requireInstallationLive() {
            if (pending != this || report != null
                || terminalPrepared
                || !installing
                || finishing
                || cancelReason != null) throw new IllegalStateException("Held GPU installation cancelled or terminal");
            requireContext();
        }

        void requireInstallationCheckpoint() {
            requireInstallationLive();
            if (!(mainLease.written ? mainLease.ownedMatches() : mainLease.originalMatches())
                || !(offLease.written ? offLease.ownedMatches() : offLease.originalMatches())) {
                ownershipIntact = false;
                throw new IllegalStateException("Held GPU equipment changed during installation");
            }
            requireInstallationLive();
        }

        void requireOwnedLease() {
            requireContext();
            if (!fixtureInstalled || !mainLease.ownedMatches() || !offLease.ownedMatches()) {
                ownershipIntact = false;
                throw new IllegalStateException(
                    "Held GPU fixture ownership lost; external equipment will not be overwritten");
            }
        }

        void observeTick() {
            requireOwnedLease();
            clientTickEnds++;
            int previous = tickSamples.get(tickSamples.size() - 1)
                .get("entityTick")
                .getAsInt();
            if (maid.ticksExisted < previous || maid.ticksExisted > previous + 1)
                throw new IllegalStateException("Held GPU observed nonconsecutive natural maid tick");
            recordTick();
        }

        void recordTick() {
            var row = new JsonObject();
            row.addProperty("clientTickIndex", clientTickEnds);
            row.addProperty("entityTick", maid.ticksExisted);
            if (!tickSamples.isEmpty() && tickSamples.get(tickSamples.size() - 1)
                .get("entityTick")
                .getAsInt() == maid.ticksExisted) tickSamples.set(tickSamples.size() - 1, row);
            else {
                if (tickSamples.size() >= 64) throw new IllegalStateException("Held GPU warmup tick observation bound");
                tickSamples.add(row);
            }
        }

        void requireCaptureModel(BuiltinMaidModel current) {
            if (naturalModel != null && current != naturalModel) {
                modelReferenceStable = false;
                throw new IllegalStateException("Held GPU natural model object changed");
            }
            if (naturalModel != null && current.getGeckoPlayer() != naturalPlayer) {
                playerReferenceStable = false;
                throw new IllegalStateException("Held GPU natural animation player object changed");
            }
        }

        void noteNaturalEntry(BuiltinMaidModel current) {
            requireOwnedLease();
            if (!current.getChoice()
                .getId()
                .equals(model)) throw new IllegalStateException("Natural body used a different model");
            requireCaptureModel(current);
            if (naturalModel == null) {
                naturalModel = current;
                naturalPlayer = current.getGeckoPlayer();
            }
            naturalRendererEntries++;
            entryPoseSerial = current.getPoseSerial();
            naturalEntryPending = true;
        }

        void observeNaturalCompletion() {
            if (!naturalEntryPending) return;
            naturalEntryPending = false;
            requireCaptureModel(naturalModel);
            if (!naturalModel.hasCompletedPoseFor(maid) || naturalModel.getPoseSerial() <= entryPoseSerial) return;
            var row = new JsonObject();
            row.addProperty("entityTick", maid.ticksExisted);
            row.addProperty("entryPoseSerial", entryPoseSerial);
            row.addProperty("completedPoseSerial", naturalModel.getPoseSerial());
            row.addProperty("modelIdentity", System.identityHashCode(naturalModel));
            row.addProperty("backend", naturalPlayer == null ? "BEDROCK" : "GECKO");
            row.addProperty("geckoPlayerPresent", naturalPlayer != null);
            var controllers = new JsonArray();
            row.add("controllers", controllers);
            if (naturalPlayer != null) {
                double seek = naturalPlayer.getSeekTicks();
                if (!Double.isFinite(seek)) throw new IllegalStateException("Non-finite natural animation clock");
                row.addProperty("geckoPlayerIdentity", System.identityHashCode(naturalPlayer));
                row.addProperty("playerSeekTicks", seek);
                for (var controller : naturalPlayer.getAnimationData()
                    .getAnimationControllers()) {
                    if (controllers.size() >= 64)
                        throw new IllegalStateException("Natural controller observation bound");
                    double offset = controller.tickOffset, speed = controller.getAnimationSpeed();
                    double derived = speed * Math.max(seek - offset, 0);
                    if (!Double.isFinite(offset) || !Double.isFinite(speed) || !Double.isFinite(derived))
                        throw new IllegalStateException("Non-finite natural controller clock");
                    var value = new JsonObject();
                    value.addProperty("name", controller.getName());
                    value.addProperty(
                        "state",
                        controller.getAnimationState()
                            .name());
                    value.addProperty("tickOffset", offset);
                    value.addProperty("animationSpeed", speed);
                    value.addProperty("shouldResetTick", controller.shouldResetTick);
                    value.addProperty("derivedUnadjustedTick", derived);
                    value.addProperty("derivedReadOnly", true);
                    var animation = controller.getCurrentAnimation();
                    value.addProperty("animationPresent", animation != null);
                    if (animation != null) {
                        if (!Double.isFinite(animation.animationLength))
                            throw new IllegalStateException("Non-finite natural clip duration");
                        value.addProperty("animationName", animation.animationName);
                        value.addProperty("animationLength", animation.animationLength);
                    }
                    controllers.add(value);
                }
            }
            if (!naturalFrames.isEmpty() && naturalFrames.get(naturalFrames.size() - 1)
                .get("entityTick")
                .getAsInt() == maid.ticksExisted) naturalFrames.set(naturalFrames.size() - 1, row);
            else {
                if (naturalFrames.size() >= 64) throw new IllegalStateException("Natural body observation bound");
                naturalFrames.add(row);
            }
            completedNaturalBodies++;
        }

        boolean ready() {
            readyForCapture = fixtureInstalled && (warmupTicks == 0
                || maid.ticksExisted - installEntityTick >= warmupTicks && clientTickEnds >= warmupTicks
                    && !naturalFrames.isEmpty()
                    && naturalFrames.get(naturalFrames.size() - 1)
                        .get("entityTick")
                        .getAsInt() >= installEntityTick + warmupTicks);
            return readyForCapture;
        }

        void finish(boolean captureComplete) {
            if (report != null || terminalPrepared) return;
            if (Thread.currentThread() != owner) {
                cancelReason = "Held GPU terminal requested off the original client thread";
                return;
            }
            if (installing || finishing) return;
            finishing = true;
            try {
                if (cancelReason != null) {
                    failureReason = cancelReason;
                    if (!evidence.has("error")) evidence.addProperty("error", cancelReason);
                } else if (evidence.has("error")) failureReason = evidence.get("error")
                    .getAsString();
                restoration = new JsonObject();
                restoration.addProperty("attempted", true);
                var main = mainLease.restore();
                var off = offLease.restore();
                restoration.add("main", main);
                restoration.add("off", off);
                boolean restored = HandLease.restored(main) && HandLease.restored(off);
                if (main.get("status")
                    .getAsString()
                    .equals("REFUSED_EXTERNAL_CHANGE")
                    || off.get("status")
                        .getAsString()
                        .equals("REFUSED_EXTERNAL_CHANGE"))
                    ownershipIntact = false;
                restoration.addProperty("complete", restored);
                evidence.add("restoredMainhand", main.get("afterRestore"));
                evidence.add("restoredOffhand", off.get("afterRestore"));
                boolean equipmentRestored = mainLease.originalValueRestored() && offLease.originalValueRestored();
                if (cancelReason != null) {
                    failureReason = cancelReason;
                    if (!evidence.has("error")) evidence.addProperty("error", cancelReason);
                }
                evidence.addProperty("equipmentValuesRestored", equipmentRestored);
                if (!restored && !evidence.has("equipmentRestoreError")) evidence.addProperty(
                    "equipmentRestoreError",
                    "Original fixture lease was not fully restored; inspect per-hand warmup restore");
                if ((!restored || !equipmentRestored) && failureReason.isEmpty())
                    failureReason = "Original equipment values or owned fixture lease were not fully restored";
                if (!captureComplete && failureReason.isEmpty())
                    failureReason = "Held GPU capture or cleanup contract incomplete";
                phase = "TERMINAL";
                evidence.add("warmup", warmupSnapshot());
                boolean complete = captureComplete && restored
                    && equipmentRestored
                    && ownershipIntact
                    && cancelReason == null
                    && captureStarted
                    && readyForCapture;
                evidence.addProperty("transactionComplete", complete);
                evidence.addProperty("status", complete ? "complete" : "failed");
                terminalPrepared = true;
                if (factoryFaultTriggered) {
                    terminalEntityTick = maid.ticksExisted;
                    terminalClientTickEndSequence = clientTickEndSequence;
                    factoryCancellationProbe.addProperty("terminalEntityTick", terminalEntityTick);
                    factoryCancellationProbe
                        .addProperty("terminalClientTickEndSequence", terminalClientTickEndSequence);
                    postTerminalTickPending = true;
                } else report = evidence;
            } finally {
                finishing = false;
            }
        }

        void observePostTerminalTick() {
            if (!postTerminalTickPending || clientTickEndSequence <= terminalClientTickEndSequence) return;
            if (Thread.currentThread() != owner) return;
            var observed = new JsonObject();
            try {
                observed.addProperty("clientTickEndSequence", clientTickEndSequence);
                observed.addProperty("entityTick", maid.ticksExisted);
                observed.addProperty("terminalWasPublished", report != null);
                observed.addProperty(
                    "sameWorldObject",
                    Minecraft.getMinecraft().theWorld == world && maid.worldObj == world);
                observed.addProperty(
                    "sameActorIdentity",
                    actor.equals(
                        maid.getUniqueID()
                            .toString())
                        && entityId == maid.getEntityId());
                observed.addProperty("registeredActorReference", world.getEntityByID(entityId) == maid);
                boolean loaded = false;
                for (Object entry : world.loadedEntityList) if (entry == maid) {
                    loaded = true;
                    break;
                }
                observed.addProperty("loadedActorReference", loaded);
                observed.addProperty("actorAlive", maid.isEntityAlive());
                observed.addProperty("addedToChunk", maid.addedToChunk);
                observed.add("actualMainhand", snapshot(mainLease.current()));
                observed.add("actualOffhand", snapshot(offLease.current()));
                boolean mainRestored = mainLease.originalValueRestored(),
                    offRestored = offLease.originalValueRestored();
                observed.addProperty("mainValueMatchesOriginal", mainRestored);
                observed.addProperty("offValueMatchesOriginal", offRestored);
                observed.addProperty("nativeEntityTickAdvanced", maid.ticksExisted > terminalEntityTick);
            } catch (RuntimeException | LinkageError failure) {
                observed.addProperty("observationError", failure.toString());
            } finally {
                factoryCancellationProbe.add("postTerminalNativeTick", observed);
                postTerminalTickPending = false;
                report = evidence;
            }
        }

        JsonObject warmupSnapshot() {
            var value = new JsonObject();
            value.addProperty("schema", "held-item-natural-warmup-v1");
            value.addProperty("requestedTicks", warmupTicks);
            value.addProperty("phase", phase);
            value.addProperty("fixtureInstalled", fixtureInstalled);
            value.addProperty("captureStarted", captureStarted);
            if (fixtureInstalled) {
                value.addProperty("installEntityTick", installEntityTick);
                value.addProperty("finalEntityTick", maid.ticksExisted);
                value.addProperty("entityTickDelta", maid.ticksExisted - installEntityTick);
            }
            value.addProperty("clientTickEnds", clientTickEnds);
            value.addProperty("naturalRendererEntries", naturalRendererEntries);
            value.addProperty("completedNaturalBodies", completedNaturalBodies);
            value.addProperty("naturalModelObserved", completedNaturalBodies > 0);
            value.addProperty("modelReferenceStable", modelReferenceStable);
            value.addProperty("playerReferenceStable", playerReferenceStable);
            value.addProperty("readyForCapture", readyForCapture);
            value.addProperty("ownershipIntact", ownershipIntact);
            value.addProperty("failureReason", failureReason);
            value.add("tickSamples", GSON.toJsonTree(tickSamples));
            value.add("naturalFrames", GSON.toJsonTree(naturalFrames));
            value.add("restore", restoration);
            return value;
        }
    }

    private static JsonObject snapshot(ItemStack stack) {
        try {
            return MaidShieldFixtures.snapshot(stack == null ? null : stack.copy());
        } catch (RuntimeException | LinkageError failure) {
            var result = new JsonObject();
            result.addProperty("present", stack != null);
            result.addProperty("snapshotError", failure.toString());
            return result;
        }
    }

    private static final class HandLease {

        final EntityMaid maid;
        final boolean offhand;
        final ItemStack originalReference, originalValue;
        ItemStack ownedReference, ownedValue;
        boolean written, referenceCaptured;

        HandLease(EntityMaid maid, boolean offhand) {
            this.maid = maid;
            this.offhand = offhand;
            originalReference = current();
            originalValue = originalReference == null ? null : originalReference.copy();
        }

        ItemStack current() {
            return offhand ? maid.getOffhandItem() : maid.getHeldItem();
        }

        boolean originalMatches() {
            return current() == originalReference && ItemStack.areItemStacksEqual(current(), originalValue);
        }

        boolean ownedMatches() {
            return written && referenceCaptured
                && current() == ownedReference
                && ItemStack.areItemStacksEqual(current(), ownedValue);
        }

        boolean originalValueRestored() {
            try {
                return ItemStack.areItemStacksEqual(current(), originalValue);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        void write(ItemStack stack, Request request) {
            var value = stack == null ? null : stack.copy();
            request.requireInstallationLive();
            ownedValue = value;
            written = true;
            set(stack);
            ownedReference = current();
            referenceCaptured = true;
            request.requireInstallationLive();
        }

        void set(ItemStack stack) {
            if (offhand) maid.setOffhandItem(stack);
            else maid.setCurrentItemOrArmor(0, stack);
        }

        JsonObject restore() {
            var result = new JsonObject();
            result.add("original", snapshot(originalValue));
            result.add("owned", snapshot(ownedValue));
            result.addProperty("referenceOwned", false);
            result.addProperty("valueOwned", false);
            result.addProperty("reason", "");
            try {
                ItemStack before = current();
                result.add("beforeRestore", snapshot(before));
                boolean reference = written && referenceCaptured && before == ownedReference;
                boolean value = written && ItemStack.areItemStacksEqual(before, ownedValue);
                result.addProperty("referenceOwned", reference);
                result.addProperty("valueOwned", value);
                if (!written) result.addProperty("status", "NOT_WRITTEN");
                else if (!reference || !value) {
                    result.addProperty("status", "REFUSED_EXTERNAL_CHANGE");
                    result.addProperty(
                        "reason",
                        "Current hand no longer matches the fixture reference and value; left untouched");
                } else {
                    var replacement = originalValue == null ? null : originalValue.copy();
                    if (!ownedMatches()) {
                        result.addProperty("status", "REFUSED_EXTERNAL_CHANGE");
                        result
                            .addProperty("reason", "Fixture hand changed while preparing restoration; left untouched");
                    } else {
                        set(replacement);
                        if (!ItemStack.areItemStacksEqual(current(), originalValue))
                            throw new IllegalStateException("Restored hand value differs from original snapshot");
                        result.addProperty("status", "RESTORED");
                    }
                }
                result.add("afterRestore", snapshot(current()));
            } catch (RuntimeException | LinkageError failure) {
                result.addProperty("status", "RESTORE_FAILED");
                result.addProperty("reason", failure.toString());
                if (!result.has("beforeRestore")) result.add("beforeRestore", snapshot(null));
                try {
                    result.add("afterRestore", snapshot(current()));
                } catch (RuntimeException | LinkageError ignored) {
                    result.add("afterRestore", snapshot(null));
                }
            }
            return result;
        }

        static boolean restored(JsonObject value) {
            String status = value.get("status")
                .getAsString();
            return status.equals("RESTORED") || status.equals("NOT_WRITTEN");
        }
    }

    public static final class Result {

        public final String request;
        public final JsonObject report;

        Result(String request, JsonObject report) {
            this.request = request;
            this.report = report;
        }
    }
}
