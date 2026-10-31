package com.tianluoqaq.tlmnh.client.development;

import java.awt.image.BufferedImage;
import java.io.InputStream;
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
import net.minecraft.world.WorldSettings.GameType;

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
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemLayer;
import com.tianluoqaq.tlmnh.client.render.MaidHeldItemTransforms.Side;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.MaidShieldFixtures;

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

    private HeldItemGpuProbe() {}

    public static boolean isPending() {
        return pending != null;
    }

    public static boolean isCapturing(EntityMaid maid) {
        var transaction = active;
        return transaction != null && transaction.request.maid == maid;
    }

    public static void arm(String request, String model, String action) {
        requireObserver();
        if (pending != null || active != null) throw new IllegalStateException("Held GPU probe already pending");
        if (!request.matches("[A-Za-z0-9_-]{1,100}\\.json")
            || !Arrays.asList(EntityMaid.DEFAULT_MODEL_ID, "geckolib:winefox")
                .contains(model)
            || !actions().contains(action)) throw new IllegalArgumentException("Unreviewed held GPU fixture");
        pending = new Request(request, model, action, soleMaid(model));
    }

    public static List<String> actions() {
        return ClientDevelopmentRequest.heldGpuActions();
    }

    public static void tickEnd() {
        if (pending == null || pending.report != null) return;
        if (++pending.ticks > 200 || System.nanoTime() > pending.deadline) cancel("Held GPU frame deadline exceeded");
    }

    public static void renderEnd(float partialTick) {
        if (pending == null || pending.report != null || active != null) return;
        var request = pending;
        var report = header(request);
        HeldItemGpuState state = null;
        Framebuffer target = null, body = null;
        Transaction transaction = null;
        ItemStack main = request.maid.getHeldItem(), off = request.maid.getOffhandItem();
        var mainValue = main == null ? null : main.copy();
        var offValue = off == null ? null : off.copy();
        report.add("originalMainhand", MaidShieldFixtures.snapshot(main));
        report.add("originalOffhand", MaidShieldFixtures.snapshot(off));
        boolean stateRestored = false, equipmentRestored = false;
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
            var fixture = item(request.action, report);
            request.maid.setCurrentItemOrArmor(0, request.side == Side.RIGHT ? fixture : null);
            request.maid.setOffhandItem(request.side == Side.LEFT ? fixture : null);
            report.add("actualMainhand", MaidShieldFixtures.snapshot(request.maid.getHeldItem()));
            report.add("actualOffhand", MaidShieldFixtures.snapshot(request.maid.getOffhandItem()));
            state = new HeldItemGpuState();
            state.push();
            try (var matrices = renderer.openDevelopmentItemStateScope()) {
                try {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                    target = new Framebuffer(SIZE, SIZE, true);
                    body = new Framebuffer(SIZE, SIZE, true);
                    target.bindFramebuffer(true);
                    target.checkFramebufferComplete();
                    configure();
                    transaction = new Transaction(request, report, target, body);
                    active = transaction;
                    renderer.doRender(request.maid, 0, 0, 0, 0, partialTick);
                    active = null;
                    if (transaction.seams != 1 || transaction.samples.size() != 5 || report.has("error"))
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
            if (transaction != null) transaction.cleanup();
            try {
                request.maid.setCurrentItemOrArmor(0, main);
                request.maid.setOffhandItem(off);
                equipmentRestored = ItemStack.areItemStacksEqual(request.maid.getHeldItem(), mainValue)
                    && ItemStack.areItemStacksEqual(request.maid.getOffhandItem(), offValue);
                report.add("restoredMainhand", MaidShieldFixtures.snapshot(request.maid.getHeldItem()));
                report.add("restoredOffhand", MaidShieldFixtures.snapshot(request.maid.getOffhandItem()));
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("equipmentRestoreError", failure.toString());
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
        report.addProperty("equipmentValuesRestored", equipmentRestored);
        report.addProperty("glStateRestored", stateRestored);
        report.addProperty(
            "transactionComplete",
            transaction != null && transaction.seams == 1
                && transaction.samples.size() == 5
                && !report.has("error")
                && !report.has("cleanupError")
                && !report.has("equipmentRestoreError")
                && !report.has("stateRestoreError")
                && !report.has("bodyDeleteError")
                && !report.has("targetDeleteError")
                && equipmentRestored
                && stateRestored);
        request.report = report;
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
        if (transaction == null || transaction.request.maid != maid) return;
        if (++transaction.rendererEntries != 1) throw new IllegalStateException("Nested actual maid renderer in pilot");
        transaction.poseBefore = model.getPoseSerial();
    }

    public static void cancel(String reason) {
        if (pending != null && pending.report == null) {
            var report = header(pending);
            report.addProperty("transactionComplete", false);
            report.addProperty("error", reason);
            pending.report = report;
        }
    }

    public static Result poll() {
        if (pending == null || pending.report == null) return null;
        var result = new Result(pending.request, pending.report);
        pending = null;
        return result;
    }

    private static JsonObject header(Request request) {
        var report = new JsonObject();
        report.addProperty("schema", "held-item-gpu-pilot-v2");
        report.addProperty("status", "complete");
        report.addProperty("transaction", request.request);
        report.addProperty("model", request.model);
        report.addProperty("action", request.action);
        report.addProperty("side", request.side.name());
        report.addProperty(
            "entityUuid",
            request.maid.getUniqueID()
                .toString());
        report.addProperty("width", SIZE);
        report.addProperty("height", SIZE);
        report.addProperty("depthEncoding", "GL_DEPTH_COMPONENT_FLOAT32_" + java.nio.ByteOrder.nativeOrder());
        report.addProperty(
            "scope",
            "one actual MaidRenderer body; same-pose production item-layer replay in no-shader FBO; real native draw skip and GPU query");
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

    private static void configure() {
        GL20.glUseProgram(0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(1);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
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
        var matrix = new Matrix4f().lookAt(0, 1, 5, 0, 1, 0, 0, 1, 0);
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

    private static byte[] depth() {
        var buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        return bytes;
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
        final Framebuffer target, body;
        final Thread owner = Thread.currentThread();
        final List<byte[]> samples = new ArrayList<>();
        final JsonArray sampleReports = new JsonArray();
        final JsonArray queryWaits = new JsonArray();
        final Path game, folder;
        MaidHeldItemLayer.DevelopmentDrawGate gate;
        JsonArray calls;
        int seams, rendererEntries, entered, completed, skipped;
        long poseBefore;
        boolean observing;

        Transaction(Request request, JsonObject report, Framebuffer target, Framebuffer body) throws Exception {
            this.request = request;
            this.report = report;
            this.target = target;
            this.body = body;
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
            report.add("queryWaits", queryWaits);
        }

        void capture(BuiltinMaidModel model, float partialTick, Runnable draw) throws Exception {
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
            gate = MaidHeldItemLayer.installDevelopmentDrawGate(
                request.maid.getUniqueID()
                    .toString(),
                request.side,
                request.request);
            MaidHeldItemLayer.setObserverForDevelopment(this);
            observing = true;
            String[] phases = { "normal0", "normal1", "normal2", "skip_native_draw", "recovery" };
            for (int index = 0; index < phases.length; index++) {
                if (System.nanoTime() > request.deadline)
                    throw new IllegalStateException("Held GPU transaction deadline");
                copy(body, target);
                byte[] beforeColor = rgba(), beforeDepth = depth();
                if (!Arrays.equals(bodyColor, beforeColor) || !Arrays.equals(bodyDepth, beforeDepth))
                    throw new IllegalStateException("Body color/depth baseline was not restored exactly");
                float[] entryView = matrix(GL11.GL_MODELVIEW_MATRIX),
                    entryProjection = matrix(GL11.GL_PROJECTION_MATRIX);
                if (!Arrays.equals(bodyView, entryView) || !Arrays.equals(projection, entryProjection))
                    throw new IllegalStateException("Body GL matrix changed before equipment draw");
                var beforeGeometry = new Geometry();
                model.renderGeometry(new LegacyPoseStack(), beforeGeometry, light);
                String beforeHash = beforeGeometry.finish();
                if (!geometry.equals(beforeHash) || request.maid.ticksExisted != tick
                    || model.getPoseSerial() != serial)
                    throw new IllegalStateException("Body pose changed before equipment draw");
                boolean skip = index == 3;
                MaidHeldItemLayer.setDevelopmentDrawSkip(gate, skip);
                calls = new JsonArray();
                entered = completed = skipped = 0;
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
                    GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                    queryActive = false;
                    var wait = new JsonObject();
                    wait.addProperty("phase", phases[index]);
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
                    var item = save(phases[index], color, afterDepth);
                    item.add("queryWait", wait);
                    item.addProperty("bodyBeforeColorSha256", hash(beforeColor));
                    item.addProperty("bodyBeforeDepthSha256", hash(beforeDepth));
                    item.addProperty("bodyBeforeGeometrySha256", beforeHash);
                    item.add("entryModelView", GSON.toJsonTree(entryView));
                    item.add("entryProjection", GSON.toJsonTree(entryProjection));
                    item.addProperty("occlusionSamples", gpuSamples);
                    item.addProperty("nativeEntered", entered);
                    item.addProperty("nativeReturned", completed);
                    item.addProperty("nativeSkipped", skipped);
                    item.add("nativeCalls", calls);
                    var afterGeometry = new Geometry();
                    model.renderGeometry(new LegacyPoseStack(), afterGeometry, light);
                    String afterHash = afterGeometry.finish();
                    float[] exitView = matrix(GL11.GL_MODELVIEW_MATRIX),
                        exitProjection = matrix(GL11.GL_PROJECTION_MATRIX);
                    item.add("exitModelView", GSON.toJsonTree(exitView));
                    item.add("exitProjection", GSON.toJsonTree(exitProjection));
                    item.addProperty("bodyAfterGeometrySha256", afterHash);
                    if (!geometry.equals(afterHash) || request.maid.ticksExisted != tick
                        || model.getPoseSerial() != serial
                        || !Arrays.equals(bodyView, exitView)
                        || !Arrays.equals(projection, exitProjection))
                        throw new IllegalStateException("Body pose changed during equipment draw");
                    samples.add(color);
                    sampleReports.add(item);
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
        }

        @Override
        public void beforeNativeCall(MaidHeldItemLayer.NativeCall call) {
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
            calls.add(row);
            if (wasEntered) entered++;
            if (returned) completed++;
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

    private static final class Request {

        final String request, model, action;
        final EntityMaid maid;
        final Side side;
        final long deadline = System.nanoTime() + 15_000_000_000L;
        int ticks;
        JsonObject report;

        Request(String request, String model, String action, EntityMaid maid) {
            this.request = request;
            this.model = model;
            this.action = action;
            this.maid = maid;
            side = action.startsWith("shield_") ? Side.LEFT : Side.RIGHT;
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
