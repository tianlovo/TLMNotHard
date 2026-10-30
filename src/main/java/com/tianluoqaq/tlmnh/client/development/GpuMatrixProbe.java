package com.tianluoqaq.tlmnh.client.development;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings.GameType;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.LegacyVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;

import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 单次实际模型调用内的固定姿态FBO实验，只验证外层矩阵缩放的像素检出 */
@SideOnly(Side.CLIENT)
public final class GpuMatrixProbe {

    private static final int SIZE = 256, LIGHT = 0x00F000F0;
    private static Request pending;

    private GpuMatrixProbe() {}

    public static void arm(String request, String model) {
        requireObserver();
        if (pending != null) throw new IllegalStateException("GPU matrix probe already pending");
        if (!request.matches("[A-Za-z0-9_-]{1,100}\\.json")
            || !Arrays.asList("geckolib:winefox_hanfu", "authors_and_credits:wine_fox_maid")
                .contains(model))
            throw new IllegalArgumentException("Unsupported GPU matrix request");
        pending = new Request(request, model, soleMaid(model));
    }

    public static boolean isPending() {
        return pending != null;
    }

    public static void tickEnd() {
        if (pending == null || pending.report != null) return;
        try {
            requireObserver();
            if (soleMaid(pending.model) != pending.maid) throw new IllegalStateException("GPU fixture actor changed");
            if (++pending.ticks > 200 || System.nanoTime() > pending.deadline)
                throw new IllegalStateException("GPU fixture did not render before deadline");
        } catch (RuntimeException failure) {
            inconclusive(failure.toString());
        }
    }

    /** 在普通模型完成动画、尚未开始本体批次的位置调用；未armed路径不访问GL */
    public static void afterPose(BuiltinMaidModel model, EntityMaid maid, float partialTick) {
        if (pending == null || pending.report != null || pending.maid != maid) return;
        var report = header();
        State before = null;
        Framebuffer target = null;
        var images = new ArrayList<Sample>();
        boolean restored = false;
        try {
            requireObserver();
            if (soleMaid(pending.model) != maid || !model.getChoice()
                .getId()
                .equals(pending.model)) throw new IllegalStateException("GPU actual actor/model identity differs");
            if (!OpenGlHelper.isFramebufferEnabled() || !GLContext.getCapabilities().OpenGL30)
                throw new IllegalStateException("Reviewed OpenGL 3 FBO path is unavailable");
            Object pack = Class.forName("net.coderbot.iris.Iris")
                .getMethod("getCurrentPack")
                .invoke(null);
            if (!(pack instanceof Optional<?>)) throw new IllegalStateException("Shader identity API is unavailable");
            if (((Optional<?>) pack).isPresent())
                throw new IllegalStateException("Shader packs are outside this GPU fixture");
            report.addProperty("shaderPackActive", false);
            report.addProperty("glVendor", GL11.glGetString(GL11.GL_VENDOR));
            report.addProperty("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
            report.addProperty("glVersion", GL11.glGetString(GL11.GL_VERSION));
            report.addProperty(
                "geometryResource",
                model.getChoice()
                    .getGeometry()
                    .toString());
            report.addProperty(
                "textureResource",
                model.getChoice()
                    .getTexture()
                    .toString());
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
            report.addProperty("actorTick", maid.ticksExisted);
            report.addProperty("partialTick", partialTick);
            var geometry = new Stream(null);
            model.renderGeometry(new LegacyPoseStack(), geometry, LIGHT);
            String expectedStream = geometry.finish();
            float width = geometry.maxX - geometry.minX, height = geometry.maxY - geometry.minY;
            if (geometry.vertices == 0 || width <= 0 || height <= 0)
                throw new IllegalStateException("Empty or degenerate actual GPU fixture geometry");
            before = new State();
            before.push();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            target = new Framebuffer(SIZE, SIZE, true);
            target.bindFramebuffer(true);
            target.checkFramebufferComplete();
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            float fit = 1.7F / Math.max(width, height);
            float cx = (geometry.minX + geometry.maxX) * 0.5F;
            float cy = (geometry.minY + geometry.maxY) * 0.5F;
            float cz = (geometry.minZ + geometry.maxZ) * 0.5F;
            String[] names = { "normal0", "normal1", "normal2", "outer_scale_0_5", "recovery" };
            for (int index = 0; index < names.length; index++) {
                if (System.nanoTime() > pending.deadline)
                    throw new IllegalStateException("GPU transaction exceeded deadline");
                configure(model);
                int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (texture == 0) throw new IllegalStateException("Actual GPU texture binding is missing");
                if (!images.isEmpty() && images.get(0).texture != texture)
                    throw new IllegalStateException("Actual texture identity changed within GPU transaction");
                GL11.glClearColor(0, 0, 0, 0);
                GL11.glClearDepth(1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                float outer = index == 3 ? 0.5F : 1;
                GL11.glScalef(outer, outer, outer);
                GL11.glScalef(fit, -fit, fit);
                GL11.glTranslatef(-cx, -cy, -cz);
                var consumer = TessellatorVertexConsumer.INSTANCE;
                var actual = new Stream(consumer);
                consumer.begin();
                try {
                    model.renderGeometry(new LegacyPoseStack(), actual, LIGHT);
                } finally {
                    consumer.end();
                }
                String local = actual.finish();
                var pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
                GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                byte[] rgba = new byte[pixels.capacity()];
                pixels.get(rgba);
                images.add(new Sample(names[index], outer, rgba, local, actual.vertices, texture));
                if (!expectedStream.equals(local) || geometry.vertices != actual.vertices)
                    throw new IllegalStateException("Local submission changed within frozen GPU transaction");
            }
            report.addProperty("localArgumentSha256", expectedStream);
            report.addProperty("localVertices", geometry.vertices);
        } catch (ReflectiveOperationException | IOException | LinkageError | RuntimeException failure) {
            report.addProperty("error", failure.toString());
        } finally {
            try {
                if (target != null) target.deleteFramebuffer();
            } catch (RuntimeException | LinkageError failure) {
                report.addProperty("cleanupError", failure.toString());
            } finally {
                if (before != null) {
                    try {
                        before.restore();
                        restored = before.matchesCurrent();
                    } catch (RuntimeException | LinkageError failure) {
                        report.addProperty("restoreError", failure.toString());
                    }
                }
            }
        }
        report.addProperty("stateRestored", restored);
        report.addProperty("samplesCaptured", images.size());
        try {
            var output = new JsonArray();
            Path game = Minecraft.getMinecraft().mcDataDir.toPath()
                .toRealPath();
            Path folder = game.resolve("tlmnh-dev/gpu-matrix")
                .resolve(pending.request.replace(".json", ""));
            if (Files.exists(folder)) throw new IOException("GPU evidence directory already exists");
            if (!images.isEmpty()) {
                Files.createDirectories(folder);
                if (!folder.toRealPath()
                    .startsWith(game)) throw new IOException("GPU evidence directory escaped runtime");
            }
            for (var image : images) output.add(image.save(folder, game));
            report.add("samples", output);
            assess(report, images, restored);
        } catch (IOException | RuntimeException failure) {
            report.addProperty("evidenceError", failure.toString());
            report.addProperty("verdict", "INCONCLUSIVE");
            report.addProperty("passed", false);
        }
        pending.report = report;
    }

    private static void configure(BuiltinMaidModel model) {
        GL20.glUseProgram(0);
        for (int unit : new int[] { GL13.GL_TEXTURE0, GL13.GL_TEXTURE1 }) {
            GL13.glActiveTexture(unit);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            for (int cap : new int[] { GL11.GL_TEXTURE_GEN_S, GL11.GL_TEXTURE_GEN_T, GL11.GL_TEXTURE_GEN_R,
                GL11.GL_TEXTURE_GEN_Q }) GL11.glDisable(cap);
            if (unit == GL13.GL_TEXTURE1) GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(
                model.getChoice()
                    .getTexture());
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glColor4f(1, 1, 1, 1);
        for (int cap : new int[] { GL11.GL_LIGHTING, GL11.GL_FOG, GL11.GL_BLEND, GL11.GL_CULL_FACE,
            GL11.GL_STENCIL_TEST, GL11.GL_SCISSOR_TEST, GL13.GL_MULTISAMPLE, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE })
            GL11.glDisable(cap);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        for (int key : State.PIXEL_STORE)
            GL11.glPixelStorei(key, key == GL11.GL_PACK_ALIGNMENT || key == GL11.GL_UNPACK_ALIGNMENT ? 1 : 0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(-1, 1, -1, 1, -100, 100);
    }

    static void assess(JsonObject report, List<Sample> images, boolean restored) {
        report.addProperty("passed", false);
        report.addProperty("verdict", "INCONCLUSIVE");
        if (report.has("error") || report.has("cleanupError")
            || report.has("restoreError")
            || !restored
            || images.size() != 5) return;
        Sample base = images.get(0), fault = images.get(3), recovery = images.get(4);
        int noise = Math.max(base.changed(images.get(1)), base.changed(images.get(2)));
        int faultDifference = base.changed(fault), recoveryDifference = base.changed(recovery);
        report.addProperty("baselineMaxChangedPixels", noise);
        report.addProperty("faultChangedPixels", faultDifference);
        report.addProperty("recoveryChangedPixels", recoveryDifference);
        report.addProperty("baselineOccupiedPixels", base.occupied);
        report.addProperty("faultOccupiedPixels", fault.occupied);
        if (base.occupied < 64 || base.edge || noise != 0) {
            report.addProperty("reason", "normal_repetition_not_stable_or_foreground_insufficient");
            return;
        }
        boolean detected = fault.occupied > 0 && fault.occupied < base.occupied * 0.5
            && fault.width() <= base.width() * 0.75
            && fault.height() <= base.height() * 0.75
            && faultDifference > noise + 16;
        boolean recovered = recoveryDifference == 0 && base.local.equals(recovery.local);
        report.addProperty("matrixFaultDetected", detected);
        report.addProperty("recoveryMatched", recovered);
        report.addProperty("passed", detected && recovered);
        report.addProperty("verdict", detected && recovered ? "PASS_BOUNDED_GPU_EXPERIMENT" : "GPU_EXPERIMENT_FAILED");
    }

    public static void cancel(String error) {
        if (pending != null && pending.report == null) inconclusive(error);
    }

    public static Result poll() {
        if (pending == null || pending.report == null) return null;
        var result = new Result(pending.request, pending.report);
        pending = null;
        return result;
    }

    private static void inconclusive(String error) {
        var report = header();
        report.addProperty("error", error);
        report.addProperty("verdict", "INCONCLUSIVE");
        report.addProperty("passed", false);
        pending.report = report;
    }

    private static JsonObject header() {
        var report = new JsonObject();
        report.addProperty("schema", "gpu-matrix-v1");
        report.addProperty("status", "complete");
        report.addProperty(
            "scope",
            "single actual local pose in same-invocation RGBA FBO; outer matrix scale fault only");
        report.addProperty("model", pending.model);
        report.addProperty(
            "entityUuid",
            pending.maid.getUniqueID()
                .toString());
        report.addProperty("transaction", pending.request);
        report.addProperty("width", SIZE);
        report.addProperty("height", SIZE);
        report.addProperty("normalRepetitions", 3);
        report.addProperty("gpuBufferDecoded", false);
        report.addProperty("fullWorldRendererValidated", false);
        report.addProperty("allRenderingVerified", false);
        report.addProperty("upstreamEquivalenceVerified", false);
        return report;
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

    private static EntityMaid soleMaid(String model) {
        var mc = Minecraft.getMinecraft();
        EntityMaid selected = null;
        for (var entity : mc.theWorld.loadedEntityList) if (entity instanceof EntityMaid maid) {
            if (selected != null) throw new IllegalStateException("GPU fixture requires exactly one maid");
            selected = maid;
        }
        if (selected == null || !selected.isEntityAlive()
            || !selected.isOwner(mc.thePlayer)
            || !selected.getModelId()
                .equals(model)
            || selected.ridingEntity != null
            || selected.riddenByEntity != null
            || selected.getDistanceSqToEntity(mc.thePlayer) > 64
            || mc.theWorld.getEntityByID(selected.getEntityId()) != selected)
            throw new IllegalStateException("Sole owned actual lab model required");
        return selected;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String resourceHash(ResourceLocation location) throws IOException {
        var hash = digest();
        try (InputStream stream = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(location)
            .getInputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = stream.read(bytes)) != -1) hash.update(bytes, 0, count);
        }
        return hex(hash.digest());
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static final class Stream implements LegacyVertexConsumer {

        final LegacyVertexConsumer delegate;
        final MessageDigest hash = digest();
        int vertices;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        Stream(LegacyVertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
            int overlay, int light, float nx, float ny, float nz) {
            if (++vertices > 65536) throw new IllegalStateException("GPU fixture vertex limit exceeded");
            for (float value : new float[] { x, y, z, red, green, blue, alpha, u, v, nx, ny, nz }) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite actual local vertex");
                integer(Float.floatToIntBits(value == 0 ? 0 : value));
            }
            integer(overlay);
            integer(light);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            if (delegate != null) delegate.vertex(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
        }

        private void integer(int value) {
            hash.update((byte) (value >>> 24));
            hash.update((byte) (value >>> 16));
            hash.update((byte) (value >>> 8));
            hash.update((byte) value);
        }

        String finish() {
            return hex(hash.digest());
        }
    }

    static final class Sample {

        final String name, local, rgbaHash;
        final float outer;
        final byte[] rgba;
        final int vertices, texture;
        int occupied, minX = SIZE, minY = SIZE, maxX = -1, maxY = -1;
        boolean edge;

        Sample(String name, float outer, byte[] rgba, String local, int vertices, int texture) {
            this.name = name;
            this.outer = outer;
            this.rgba = rgba;
            this.local = local;
            this.vertices = vertices;
            this.texture = texture;
            rgbaHash = hex(digest().digest(rgba));
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++) if ((rgba[(y * SIZE + x) * 4 + 3] & 255) != 0) {
                    occupied++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, SIZE - 1 - y);
                    maxY = Math.max(maxY, SIZE - 1 - y);
                }
            edge = minX == 0 || minY == 0 || maxX == SIZE - 1 || maxY == SIZE - 1;
        }

        int width() {
            return occupied == 0 ? 0 : maxX - minX + 1;
        }

        int height() {
            return occupied == 0 ? 0 : maxY - minY + 1;
        }

        int changed(Sample other) {
            int changed = 0;
            for (int i = 0; i < rgba.length; i += 4) if (rgba[i] != other.rgba[i] || rgba[i + 1] != other.rgba[i + 1]
                || rgba[i + 2] != other.rgba[i + 2]
                || rgba[i + 3] != other.rgba[i + 3]) changed++;
            return changed;
        }

        JsonObject save(Path directory, Path game) throws IOException {
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int i = (y * SIZE + x) * 4;
                int argb = (rgba[i + 3] & 255) << 24 | (rgba[i] & 255) << 16
                    | (rgba[i + 1] & 255) << 8
                    | rgba[i + 2] & 255;
                image.setRGB(x, SIZE - 1 - y, argb);
            }
            Path file = directory.resolve(name + ".png");
            if (!ImageIO.write(image, "png", file.toFile())) throw new IOException("PNG writer unavailable");
            var report = new JsonObject();
            report.addProperty("name", name);
            report.addProperty("outerScale", outer);
            report.addProperty("localArgumentSha256", local);
            report.addProperty("vertices", vertices);
            report.addProperty("textureBeforeDraw", texture);
            report.addProperty("rgbaSha256", rgbaHash);
            report.addProperty("rgbaOrigin", "bottom_left");
            report.addProperty("occupiedPixels", occupied);
            report.addProperty("bboxOrigin", "top_left");
            var bounds = new JsonArray();
            for (int value : new int[] { minX, minY, maxX, maxY }) bounds.add(new JsonPrimitive(value));
            report.add("bbox", bounds);
            report.addProperty("touchesEdge", edge);
            report.addProperty(
                "image",
                game.relativize(file)
                    .toString()
                    .replace('\\', '/'));
            report.addProperty("imageSha256", hex(digest().digest(Files.readAllBytes(file))));
            return report;
        }
    }

    /** GL attrib不包含FBO、矩阵、program和PBO，这些显式保存和恢复 */
    private static final class State {

        static final int[] PIXEL_STORE = { GL11.GL_PACK_ALIGNMENT, GL11.GL_UNPACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST,
            GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SWAP_BYTES,
            GL11.GL_UNPACK_LSB_FIRST };
        static final int[] ENABLES = { GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_ALPHA_TEST,
            GL11.GL_STENCIL_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_LIGHTING, GL11.GL_FOG, GL13.GL_MULTISAMPLE,
            GL13.GL_SAMPLE_ALPHA_TO_COVERAGE };
        static final int[] SCALARS = { GL11.GL_ALPHA_TEST_FUNC, GL11.GL_DEPTH_FUNC, GL11.GL_DEPTH_WRITEMASK,
            GL11.GL_DRAW_BUFFER, GL11.GL_READ_BUFFER };
        final int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING),
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int clientActive = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        final int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),
            unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int elements = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        final int[] viewport = integers(GL11.GL_VIEWPORT, 4), stores = new int[PIXEL_STORE.length];
        final int[] scalar = new int[SCALARS.length], colorMask = integers(GL11.GL_COLOR_WRITEMASK, 4);
        final float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF),
            clearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
        final float[] clearColor = floats(GL11.GL_COLOR_CLEAR_VALUE, 4);
        final float[] model = floats(GL11.GL_MODELVIEW_MATRIX, 16), projection = floats(GL11.GL_PROJECTION_MATRIX, 16);
        final float[] color = floats(GL11.GL_CURRENT_COLOR, 4);
        final int[] units = { GL13.GL_TEXTURE0, GL13.GL_TEXTURE1, active }, textures = new int[3];
        final float[][] textureMatrices = new float[3][];
        final boolean[] enabled = new boolean[ENABLES.length], textureEnabled = new boolean[3];
        final List<String> restoreFailures = new ArrayList<>();
        boolean pushed, clientPushed;

        State() {
            for (int i = 0; i < PIXEL_STORE.length; i++) stores[i] = GL11.glGetInteger(PIXEL_STORE[i]);
            for (int i = 0; i < SCALARS.length; i++) scalar[i] = GL11.glGetInteger(SCALARS[i]);
            for (int i = 0; i < ENABLES.length; i++) enabled[i] = GL11.glIsEnabled(ENABLES[i]);
            try {
                for (int i = 0; i < units.length; i++) {
                    GL13.glActiveTexture(units[i]);
                    textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    textureMatrices[i] = floats(GL11.GL_TEXTURE_MATRIX, 16);
                    textureEnabled[i] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                }
            } finally {
                GL13.glActiveTexture(active);
            }
        }

        void push() {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            pushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientPushed = true;
        }

        void restore() {
            // PopAttrib也恢复draw/read buffer，须先绑定进入事务前的FBO，不能对默认0恢复附件枚举
            restoreStep(() -> GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo));
            restoreStep(() -> GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo));
            restoreStep(() -> GL30.glBindVertexArray(vao));
            if (clientPushed) {
                restoreStep(GL11::glPopClientAttrib);
                clientPushed = false;
            }
            if (pushed) {
                restoreStep(GL11::glPopAttrib);
                pushed = false;
            }
            restoreStep(() -> GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo));
            restoreStep(() -> GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo));
            restoreStep(() -> GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer));
            restoreStep(() -> GL20.glUseProgram(program));
            restoreStep(() -> GL30.glBindVertexArray(vao));
            restoreStep(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, array));
            restoreStep(() -> GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elements));
            restoreStep(() -> GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer));
            restoreStep(() -> GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer));
            for (int i = 0; i < PIXEL_STORE.length; i++) {
                final int index = i;
                restoreStep(() -> GL11.glPixelStorei(PIXEL_STORE[index], stores[index]));
            }
            for (int i = 0; i < units.length; i++) {
                final int index = i;
                restoreStep(() -> {
                    GL13.glActiveTexture(units[index]);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[index]);
                    GL11.glMatrixMode(GL11.GL_TEXTURE);
                    load(textureMatrices[index]);
                    if (textureEnabled[index]) GL11.glEnable(GL11.GL_TEXTURE_2D);
                    else GL11.glDisable(GL11.GL_TEXTURE_2D);
                });
            }
            restoreStep(() -> GL13.glActiveTexture(active));
            restoreStep(() -> GL13.glClientActiveTexture(clientActive));
            restoreStep(() -> {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                load(projection);
            });
            restoreStep(() -> {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                load(model);
            });
            restoreStep(() -> GL11.glMatrixMode(mode));
            restoreStep(() -> GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]));
            restoreStep(() -> GL11.glColor4f(color[0], color[1], color[2], color[3]));
            if (!restoreFailures.isEmpty()) throw new IllegalStateException(String.join("; ", restoreFailures));
        }

        private void restoreStep(Runnable step) {
            try {
                step.run();
            } catch (RuntimeException | LinkageError failure) {
                restoreFailures.add(failure.toString());
            }
        }

        boolean matchesCurrent() {
            var now = new State();
            return drawFbo == now.drawFbo && readFbo == now.readFbo
                && renderbuffer == now.renderbuffer
                && program == now.program
                && mode == now.mode
                && active == now.active
                && clientActive == now.clientActive
                && packBuffer == now.packBuffer
                && unpackBuffer == now.unpackBuffer
                && vao == now.vao
                && array == now.array
                && elements == now.elements
                && Arrays.equals(viewport, now.viewport)
                && Arrays.equals(stores, now.stores)
                && Arrays.equals(model, now.model)
                && Arrays.equals(projection, now.projection)
                && Arrays.equals(color, now.color)
                && Arrays.equals(enabled, now.enabled)
                && Arrays.equals(scalar, now.scalar)
                && Arrays.equals(colorMask, now.colorMask)
                && alphaRef == now.alphaRef
                && clearDepth == now.clearDepth
                && Arrays.equals(clearColor, now.clearColor)
                && Arrays.equals(textures, now.textures)
                && Arrays.equals(textureEnabled, now.textureEnabled)
                && Arrays.deepEquals(textureMatrices, now.textureMatrices);
        }

        private static int[] integers(int name, int count) {
            IntBuffer buffer = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(name, buffer);
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = buffer.get(i);
            return values;
        }

        private static float[] floats(int name, int count) {
            FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(name, buffer);
            float[] values = new float[count];
            for (int i = 0; i < count; i++) values[i] = buffer.get(i);
            return values;
        }

        private static void load(float[] values) {
            FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
            buffer.put(values)
                .flip();
            GL11.glLoadMatrix(buffer);
        }
    }

    private static final class Request {

        final String request, model;
        final EntityMaid maid;
        final long deadline = System.nanoTime() + 10_000_000_000L;
        int ticks;
        JsonObject report;

        Request(String request, String model, EntityMaid maid) {
            this.request = request;
            this.model = model;
            this.maid = maid;
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
