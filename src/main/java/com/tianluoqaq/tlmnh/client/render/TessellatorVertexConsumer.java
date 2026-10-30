package com.tianluoqaq.tlmnh.client.render;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.development.RenderSubmissionProbe;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 使用标准实体 Tessellator 顶点格式，让 Angelica 接管其现有渲染路径 */
@SideOnly(Side.CLIENT)
public final class TessellatorVertexConsumer implements LegacyVertexConsumer {

    public static final TessellatorVertexConsumer INSTANCE = new TessellatorVertexConsumer();
    private final FloatBuffer color = BufferUtils.createFloatBuffer(4);
    private final Tessellator tessellator = Tessellator.instance;
    private boolean active;

    private TessellatorVertexConsumer() {}

    public void begin() {
        if (active) throw new IllegalStateException("Nested TLMNH model batch");
        color.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        tessellator.startDrawingQuads();
        active = true;
    }

    public void end() {
        if (!active) throw new IllegalStateException("No active TLMNH model batch");
        try {
            RenderSubmissionProbe.beforeDraw();
            RenderSubmissionProbe.drawn(tessellator.draw());
        } finally {
            active = false;
        }
    }

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
        int overlay, int lightmap, float normalX, float normalY, float normalZ) {
        if (!active) throw new IllegalStateException("TLMNH vertex outside model batch");
        if (RenderSubmissionProbe.dropVertex()) return;
        u = RenderSubmissionProbe.submittedU(u);
        double length = Math.sqrt((double) normalX * normalX + (double) normalY * normalY + (double) normalZ * normalZ);
        if (length > 0.0) {
            normalX = (float) (normalX / length);
            normalY = (float) (normalY / length);
            normalZ = (float) (normalZ / length);
        }
        red *= color.get(0);
        green *= color.get(1);
        blue *= color.get(2);
        alpha *= color.get(3);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.setBrightness(lightmap);
        tessellator.setNormal(normalX, normalY, normalZ);
        RenderSubmissionProbe
            .submitted(x, y, z, red, green, blue, alpha, u, v, overlay, lightmap, normalX, normalY, normalZ);
        tessellator.addVertexWithUV(x, y, z, u, v);
    }
}
