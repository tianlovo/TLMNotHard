package com.tianluoqaq.tlmnh.client.render;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import com.tianluoqaq.tlmnh.compat.angelica.client.render.MaidRenderPlatformState;

/** 已支持物品的GL边界，矩阵栈与attrib分开恢复，不修改FBO或program */
public final class MaidItemRenderScope implements AutoCloseable {

    private final int matrixMode;
    private final int activeTexture;
    private final MatrixState modelView, projection;
    private final List<TextureState> textures = new ArrayList<>();
    private boolean attributesPushed, clientAttributesPushed, closed;

    public MaidItemRenderScope() {
        if (!MaidRenderProtection.ready()) throw new IllegalStateException("Native render state unavailable");
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            modelView = MatrixState.capture(GL11.GL_MODELVIEW);
            projection = MatrixState.capture(GL11.GL_PROJECTION);
            captureTexture(OpenGlHelper.defaultTexUnit);
            captureTexture(OpenGlHelper.lightmapTexUnit);
            captureTexture(activeTexture);
        } finally {
            OpenGlHelper.setActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            clientAttributesPushed = true;
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void captureTexture(int unit) {
        for (var texture : textures) if (texture.unit == unit) return;
        OpenGlHelper.setActiveTexture(unit);
        textures.add(new TextureState(unit, MatrixState.capture(GL11.GL_TEXTURE)));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (var texture : textures) failure = restore(() -> {
            OpenGlHelper.setActiveTexture(texture.unit);
            texture.matrix.restore();
        }, failure);
        failure = restore(projection::restore, failure);
        failure = restore(modelView::restore, failure);
        if (clientAttributesPushed) failure = restore(GL11::glPopClientAttrib, failure);
        if (attributesPushed) failure = restore(GL11::glPopAttrib, failure);
        failure = restore(() -> OpenGlHelper.setActiveTexture(activeTexture), failure);
        failure = restore(() -> GL11.glMatrixMode(matrixMode), failure);
        if (failure != null) MaidRenderProtection.failed(failure);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static Throwable restore(Runnable action, Throwable previous) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (previous == null) return failure;
            if (failure != previous) previous.addSuppressed(failure);
        }
        return previous;
    }

    private static final class TextureState {

        private final int unit;
        private final MatrixState matrix;

        private TextureState(int unit, MatrixState matrix) {
            this.unit = unit;
            this.matrix = matrix;
        }
    }

    private static final class MatrixState {

        private final int mode, depth, depthName;
        private final FloatBuffer top;

        private MatrixState(int mode, int depthName, int matrixName) {
            this.mode = mode;
            this.depthName = depthName;
            GL11.glMatrixMode(mode);
            // Angelica的TEXTURE软件栈深度不能用driver兼容查询冒充
            depth = MaidRenderPlatformState.matrixDepth(depthName);
            top = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(matrixName, top);
        }

        private static MatrixState capture(int mode) {
            return switch (mode) {
                case GL11.GL_MODELVIEW -> new MatrixState(
                    mode,
                    GL11.GL_MODELVIEW_STACK_DEPTH,
                    GL11.GL_MODELVIEW_MATRIX);
                case GL11.GL_PROJECTION -> new MatrixState(
                    mode,
                    GL11.GL_PROJECTION_STACK_DEPTH,
                    GL11.GL_PROJECTION_MATRIX);
                case GL11.GL_TEXTURE -> new MatrixState(mode, GL11.GL_TEXTURE_STACK_DEPTH, GL11.GL_TEXTURE_MATRIX);
                default -> throw new IllegalArgumentException("Unknown item matrix mode");
            };
        }

        private void restore() {
            GL11.glMatrixMode(mode);
            int current = MaidRenderPlatformState.matrixDepth(depthName);
            if (current < depth) {
                // 被回调弹掉的caller下层矩阵不可凭一份top快照重建
                GL11.glLoadMatrix(top.duplicate());
                throw new IllegalStateException("Native item renderer removed caller matrix entries");
            }
            if (current - depth > 128) throw new IllegalStateException("Unbounded native item matrix leak");
            while (current-- > depth) GL11.glPopMatrix();
            GL11.glLoadMatrix(top.duplicate());
        }
    }
}
