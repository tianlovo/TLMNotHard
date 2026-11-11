package com.tianluoqaq.tlmnh.client.render.buffer;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.diagnostic.ClientDevelopmentAccess;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 使用标准实体 Tessellator 顶点格式，让 Angelica 接管其现有渲染路径 */
@SideOnly(Side.CLIENT)
public final class TessellatorVertexConsumer implements LegacyVertexConsumer {

    public static final TessellatorVertexConsumer INSTANCE = new TessellatorVertexConsumer();
    private final FloatBuffer color = BufferUtils
        .createFloatBuffer(RenderBufferLayout.TESSELLATOR_VERTEX_CREATE_FLOAT_BUFFER_FIRST_PARAM);
    private final Tessellator tessellator = Tessellator.instance;
    private boolean active;

    private TessellatorVertexConsumer() {}

    public void begin() {
        if (active) throw new IllegalStateException(RenderBufferDiagnostics.MSG_NESTED_TLMNH_MODEL_BATCH);
        color.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        tessellator.startDrawingQuads();
        active = true;
    }

    public void end() {
        if (!active) throw new IllegalStateException(RenderBufferDiagnostics.MSG_NO_ACTIVE_TLMNH_MODEL_BATCH);
        try {
            if (ClientDevelopmentAccess.isActive()) {
                var hooks = ClientDevelopmentAccess.hooks();
                hooks.submissionBeforeDraw();
                hooks.submissionDrawn(tessellator.draw());
            } else {
                tessellator.draw();
            }
        } finally {
            active = false;
        }
    }

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
        int overlay, int lightmap, float normalX, float normalY, float normalZ) {
        if (!active) throw new IllegalStateException(RenderBufferDiagnostics.MSG_TLMNH_VERTEX_OUTSIDE_MODEL_BATCH);
        boolean devActive = ClientDevelopmentAccess.isActive();
        if (devActive) {
            var hooks = ClientDevelopmentAccess.hooks();
            if (hooks.submissionDropVertex()) return;
            u = hooks.submissionModifyU(u);
        }
        double length = Math.sqrt((double) normalX * normalX + (double) normalY * normalY + (double) normalZ * normalZ);
        if (length > 0.0) {
            normalX = (float) (normalX / length);
            normalY = (float) (normalY / length);
            normalZ = (float) (normalZ / length);
        }
        red *= color.get(RenderBufferLayout.GET_INITIAL_VALUE);
        green *= color.get(RenderBufferLayout.TARGET_SIZE_THRESHOLD);
        blue *= color.get(RenderBufferLayout.TESSELLATOR_VERTEX_GET_FIRST_PARAM);
        alpha *= color.get(RenderBufferLayout.TESSELLATOR_VERTEX_GET_FIRST_PARAM_2);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.setBrightness(lightmap);
        tessellator.setNormal(normalX, normalY, normalZ);
        if (devActive) {
            ClientDevelopmentAccess.hooks()
                .submissionVertex(x, y, z, red, green, blue, alpha, u, v, overlay, lightmap, normalX, normalY, normalZ);
        }
        tessellator.addVertexWithUV(x, y, z, u, v);
    }
}
