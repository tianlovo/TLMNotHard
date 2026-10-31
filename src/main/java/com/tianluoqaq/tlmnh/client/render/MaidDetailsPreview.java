package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.touhoulittlemaid.client.model.DebugFloorModel;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原版详情变换与地板/低凳的旧版适配，每次绘制恢复相机、矩阵、光照和材质状态 */
@SideOnly(Side.CLIENT)
public final class MaidDetailsPreview implements AutoCloseable {

    private static final DebugFloorModel FLOOR = new DebugFloorModel();
    private static final ResourceLocation FLOOR_TEXTURE = new ResourceLocation(
        "touhou_little_maid",
        "textures/entity/debug_floor.png");
    private static final ResourceLocation STOOL_TEXTURE = new ResourceLocation(
        "touhou_little_maid",
        "textures/entity/low_stool.png");
    private static final ResourceLocation STOOL_MODEL = new ResourceLocation(
        "touhou_little_maid",
        "models/entity/low_stool.json");
    private AbstractBedrockEntityModel<Entity> stool;
    private int resourceRevision = -1;
    private com.google.gson.JsonObject diagnostic = new com.google.gson.JsonObject();

    public void draw(EntityMaid maid, float x, float y, float scale, float yaw, float pitch, boolean floor,
        float partialTick) {
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        int depth = MaidRenderPlatformState.matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH);
        var filters = MaidRenderPlatformState.captureFilterWindow();
        var manager = RenderManager.instance;
        float viewX = manager.playerViewX, viewY = manager.playerViewY;
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glTranslatef(x, y, 1050);
            GL11.glScalef(1, 1, -1);
            GL11.glTranslatef(0, 0, 1000);
            GL11.glScalef(scale, scale, scale);
            GL11.glRotatef(-180, 0, 0, 1);
            GL11.glRotatef(yaw, 0, 1, 0);
            GL11.glRotatef(-pitch, 1, 0, 0);
            RenderHelper.enableStandardItemLighting();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            manager.playerViewX = pitch;
            manager.playerViewY = 0;
            // 原版详情关闭实体阴影；直接走生物本体绘制，保留其前后事件和装备层
            var renderer = manager.getEntityRenderObject(maid);
            long before = MaidHeldItemLayer.getNativeCounters().attempts;
            renderer.doRender(maid, 0, 0, 0, 0, partialTick);
            if (Boolean.getBoolean("tlmnh.devCommands") && renderer instanceof MaidRenderer maidRenderer) {
                diagnostic = maidRenderer.inspectPreviewForDevelopment(maid);
                diagnostic.addProperty("nativeItemCalls", MaidHeldItemLayer.getNativeCounters().attempts - before);
            }
            GL11.glTranslatef(0, 0.5F, 0);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glDisable(GL11.GL_CULL_FACE);
            if (floor) {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(FLOOR_TEXTURE);
                FLOOR.draw();
            }
            if (maid.ridingEntity != null) drawStool();
        } finally {
            manager.playerViewX = viewX;
            manager.playerViewY = viewY;
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            while (MaidRenderPlatformState.matrixDepth(GL11.GL_MODELVIEW_STACK_DEPTH) > depth) GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glMatrixMode(mode);
            filters.restore();
        }
    }

    private void drawStool() {
        var mc = Minecraft.getMinecraft();
        int revision = BuiltinModelNames.INSTANCE.getRevision();
        if (stool == null || revision != resourceRevision) {
            try (var stream = mc.getResourceManager()
                .getResource(STOOL_MODEL)
                .getInputStream()) {
                stool = new AbstractBedrockEntityModel<Entity>(stream) {

                    @Override
                    public void setupAnim(Entity entity, float swing, float amount, float age, float yaw,
                        float pitch) {}
                };
                resourceRevision = revision;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load builtin preview stool", failure);
            }
        }
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(0, -0.95F, 0);
            GL11.glRotatef(180, 0, 1, 0);
            GL11.glScalef(-1, -1, 1);
            GL11.glTranslatef(0, -1.501F, 0);
            mc.getTextureManager()
                .bindTexture(STOOL_TEXTURE);
            var consumer = TessellatorVertexConsumer.INSTANCE;
            consumer.begin();
            try {
                stool.renderToBuffer(new LegacyPoseStack(), consumer, 0xF000F0, 0, 1, 1, 1, 1);
            } finally {
                consumer.end();
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    public void axes(int x, int y) {
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, -20);
            GL11.glRotatef(-RenderManager.instance.playerViewX, 1, 0, 0);
            GL11.glRotatef(RenderManager.instance.playerViewY, 0, 1, 0);
            GL11.glScalef(-1, -1, -1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            drawAxes(4, false);
            drawAxes(2, true);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glMatrixMode(mode);
        }
    }

    private static void drawAxes(float width, boolean colored) {
        GL11.glLineWidth(width);
        var tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);
        for (int i = 0; i < 3; i++) {
            float x = i == 0 ? 1 : 0, y = i == 1 ? 1 : 0, z = i == 2 ? 1 : 0;
            tessellator.setColorRGBA_F(colored ? x : 0, colored ? y : 0, colored ? z : 0, 1);
            tessellator.setNormal(x, y, z);
            tessellator.addVertex(0, 0, 0);
            tessellator.addVertex(x * 10, y * 10, z * 10);
        }
        tessellator.draw();
    }

    public com.google.gson.JsonObject observation() {
        return new com.google.gson.JsonParser().parse(diagnostic.toString())
            .getAsJsonObject();
    }

    @Override
    public void close() {
        stool = null;
    }
}
