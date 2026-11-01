package com.tianluoqaq.tlmnh.client.development;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** 复用GpuMatrixProbe已审计的FBO/PBO/attribute事务，不改旧实验的实现或结论 */
final class HeldItemGpuState {

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
    final int clientActive = clientActiveTexture();
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

    HeldItemGpuState() {
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
        var now = new HeldItemGpuState();
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

    private static int clientActiveTexture() {
        try {
            var manager = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
            try {
                manager = Class.forName("com.gtnewhorizons.angelica.glsm.GLSMTextures");
            } catch (ClassNotFoundException legacy) {
                // RC2 的查询仍在 GLStateManager；正式版拆到 GLSMTextures
            }
            return GL13.GL_TEXTURE0 + (Integer) manager.getMethod("getClientActiveTextureUnit")
                .invoke(null);
        } catch (ClassNotFoundException absent) {
            return GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot observe actual client texture unit", failure);
        }
    }
}
