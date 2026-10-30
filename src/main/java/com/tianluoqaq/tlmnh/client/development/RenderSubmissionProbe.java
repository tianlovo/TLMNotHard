package com.tianluoqaq.tlmnh.client.development;

import java.nio.FloatBuffer;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.BuiltinMaidModel;
import com.tianluoqaq.tlmnh.client.render.LegacyPoseStack;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 安全虚空中的一次本体批次检查；记录实际适配器调用，尚不读取GPU缓冲或像素 */
@SideOnly(Side.CLIENT)
public final class RenderSubmissionProbe {

    private static Request pending;
    private static Batch batch;

    private RenderSubmissionProbe() {}

    public static void arm(String request, String model, String fault) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development probe disabled");
        if (pending != null) throw new IllegalStateException("Submission probe already pending");
        requireObserver();
        EntityMaid selected = null;
        for (var entity : Minecraft.getMinecraft().theWorld.loadedEntityList) if (entity instanceof EntityMaid maid) {
            if (selected != null) throw new IllegalStateException("Submission fixture requires exactly one maid");
            selected = maid;
        }
        if (selected == null || !selected.getModelId()
            .equals(model)) throw new IllegalStateException("Requested model is not the sole actual lab maid");
        pending = new Request(request, model, fault, selected.getUniqueID());
    }

    public static void begin(BuiltinMaidModel model, Entity entity, int light) {
        if (pending == null || pending.report != null
            || !entity.getUniqueID()
                .equals(pending.maid))
            return;
        try {
            requireObserver();
            if (!model.getChoice()
                .getId()
                .equals(pending.model)) throw new IllegalStateException("Actual model fallback");
            var minecraft = Minecraft.getMinecraft();
            var texture = minecraft.getTextureManager()
                .getTexture(
                    model.getChoice()
                        .getTexture());
            if (texture == null) throw new IllegalStateException("Actual model texture object missing");
            batch = new Batch(texture.getGlTextureId());
            var maid = (EntityMaid) entity;
            var actor = new JsonObject();
            actor.addProperty("x", maid.posX);
            actor.addProperty("y", maid.posY);
            actor.addProperty("z", maid.posZ);
            actor.addProperty("yaw", maid.rotationYaw);
            actor.addProperty("previousYaw", maid.prevRotationYaw);
            actor.addProperty("renderYawOffset", maid.renderYawOffset);
            actor.addProperty("previousRenderYawOffset", maid.prevRenderYawOffset);
            actor.addProperty("headYaw", maid.rotationYawHead);
            actor.addProperty("previousHeadYaw", maid.prevRotationYawHead);
            actor.addProperty("requestedModel", maid.getModelId());
            actor.addProperty(
                "actualModel",
                model.getChoice()
                    .getId());
            batch.actor = actor;
            model.renderGeometry(new LegacyPoseStack(), batch.trace.expectedConsumer(), light);
            if (pending.fault.equals("wrong_texture")) bindDefaultTexture(0);
            batch.initialTexture = defaultTexture();
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    public static float submittedU(float original) {
        return batch != null && pending.fault.equals("uv_offset") ? original + 0.125F : original;
    }

    public static boolean dropVertex() {
        return batch != null && pending.fault.equals("drop_geometry");
    }

    public static void submitted(float x, float y, float z, float red, float green, float blue, float alpha, float u,
        float v, int overlay, int light, float nx, float ny, float nz) {
        if (batch == null) return;
        try {
            if (batch.vertexCalls++ == 0) batch.firstVertexTexture = defaultTexture();
            batch.trace.submitted(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
        } catch (RuntimeException error) {
            batch.error = error.toString();
        }
    }

    public static void drawn(int returnedBytes) {
        if (batch == null) return;
        batch.drawCalls++;
        batch.returnedBytes = returnedBytes;
    }

    public static void beforeDraw() {
        if (batch != null) batch.actualTexture = defaultTexture();
    }

    public static void finish(boolean completed) {
        if (batch == null) return;
        var captured = batch;
        batch = null;
        try {
            var result = header();
            result.addProperty("status", completed && captured.error == null ? "complete" : "failed");
            if (captured.error != null) result.addProperty("error", captured.error);
            result.addProperty("expectedTextureId", captured.expectedTexture);
            result.addProperty("actualTextureIdAtBatch", captured.initialTexture);
            result.addProperty("actualTextureIdAtFirstVertex", captured.firstVertexTexture);
            result.addProperty("actualTextureIdBeforeDraw", captured.actualTexture);
            result.addProperty("textureBeforeProbe", captured.previousTexture);
            result.addProperty("activeTextureBeforeProbe", captured.previousActive);
            result.addProperty("drawCalls", captured.drawCalls);
            result.addProperty("drawReturnedBytes", captured.returnedBytes);
            result.add("actor", captured.actor);
            var trace = captured.trace.finish();
            result.add("vertices", trace);
            bindDefaultTexture(captured.previousTexture);
            GL13.glActiveTexture(captured.previousActive);
            boolean restored = defaultTexture() == captured.previousTexture
                && GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == captured.previousActive;
            result.addProperty("glBindingRestored", restored);
            result.addProperty(
                "textureMatched",
                captured.expectedTexture != 0 && captured.expectedTexture == captured.actualTexture);
            result.addProperty(
                "bridgeSubmissionMatched",
                completed && captured.error == null
                    && captured.drawCalls == 1
                    && restored
                    && captured.expectedTexture != 0
                    && captured.expectedTexture == captured.actualTexture
                    && trace.get("matched")
                        .getAsBoolean());
            pending.report = result;
        } finally {
            bindDefaultTexture(captured.previousTexture);
            GL13.glActiveTexture(captured.previousActive);
        }
    }

    public static Result poll() {
        if (pending == null) return null;
        if (pending.report == null && System.nanoTime() > pending.deadline)
            fail("Requested maid did not submit a captured model batch before timeout");
        if (pending.report == null) return null;
        var result = new Result(pending.request, pending.report);
        pending = null;
        return result;
    }

    private static void fail(String error) {
        if (batch != null) {
            bindDefaultTexture(batch.previousTexture);
            GL13.glActiveTexture(batch.previousActive);
            batch = null;
        }
        var result = header();
        result.addProperty("status", "failed");
        result.addProperty("error", error);
        result.addProperty("bridgeSubmissionMatched", false);
        pending.report = result;
    }

    private static JsonObject header() {
        var result = new JsonObject();
        result.addProperty("schema", "render-submission-v0");
        result
            .addProperty("scope", "same-pose geometry replay versus actual Tessellator adapter arguments and binding");
        result.addProperty("sharedGeometryOracle", true);
        result.addProperty("capturesGpuBuffer", false);
        result.addProperty("overlayApplied", false);
        result.addProperty("renderingVerified", false);
        result.addProperty("upstreamEquivalenceVerified", false);
        result.addProperty("model", pending.model);
        result.addProperty("faultInjection", pending.fault);
        result.addProperty("entityUuid", pending.maid.toString());
        return result;
    }

    private static void requireObserver() {
        var minecraft = Minecraft.getMinecraft();
        var player = minecraft.thePlayer;
        if (!AnimationLab.isLab(minecraft.theWorld) || player == null
            || minecraft.currentScreen != null
            || !player.capabilities.disableDamage
            || !player.capabilities.isFlying
            || !player.noClip) throw new IllegalStateException("Unobscured safe lab observer required");
    }

    private static int defaultTexture() {
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } finally {
            GL13.glActiveTexture(previous);
        }
    }

    private static void bindDefaultTexture(int texture) {
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        } finally {
            GL13.glActiveTexture(previous);
        }
    }

    public static final class Result {

        public final String request;
        public final JsonObject report;

        private Result(String request, JsonObject report) {
            this.request = request;
            this.report = report;
        }
    }

    private static final class Request {

        final String request, model, fault;
        final UUID maid;
        final long deadline = System.nanoTime() + 20_000_000_000L;
        JsonObject report;

        Request(String request, String model, String fault, UUID maid) {
            this.request = request;
            this.model = model;
            this.fault = fault;
            this.maid = maid;
        }
    }

    private static final class Batch {

        final int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int previousTexture = defaultTexture();
        final int expectedTexture;
        final RenderSubmissionTrace trace;
        int initialTexture, actualTexture, drawCalls, returnedBytes, vertexCalls;
        int firstVertexTexture = -1;
        String error;
        JsonObject actor;

        Batch(int texture) {
            expectedTexture = texture;
            FloatBuffer color = BufferUtils.createFloatBuffer(4);
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            trace = new RenderSubmissionTrace(color.get(0), color.get(1), color.get(2), color.get(3));
        }
    }
}
