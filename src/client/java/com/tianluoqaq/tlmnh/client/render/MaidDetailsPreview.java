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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.render.attachment.MaidHeldItemLayer;
import com.tianluoqaq.tlmnh.client.render.buffer.LegacyPoseStack;
import com.tianluoqaq.tlmnh.client.render.buffer.TessellatorVertexConsumer;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidRenderProtection;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原版详情变换与地板/低凳的旧版适配，每次绘制恢复相机、矩阵、光照和材质状态 */
@SideOnly(Side.CLIENT)
public final class MaidDetailsPreview implements AutoCloseable {

    private static final DebugFloorModel FLOOR = new DebugFloorModel();
    private static final ResourceLocation FLOOR_TEXTURE = new ResourceLocation(
        ClientRenderIds.ID_TOUHOU_LITTLE_MAID,
        ClientRenderIds.TEXTURE_DEBUG_FLOOR);
    private static final ResourceLocation STOOL_TEXTURE = new ResourceLocation(
        ClientRenderIds.ID_TOUHOU_LITTLE_MAID,
        ClientRenderIds.TEXTURE_LOW_STOOL);
    private static final ResourceLocation STOOL_MODEL = new ResourceLocation(
        ClientRenderIds.ID_TOUHOU_LITTLE_MAID,
        ClientRenderIds.MODEL_JSON_LOW_STOOL);
    private AbstractBedrockEntityModel<Entity> stool;
    private int resourceRevision = ClientRenderLayout.UNSET_OR_NOT_FOUND;
    private JsonObject diagnostic = new JsonObject();

    public void draw(EntityMaid maid, float x, float y, float scale, float yaw, float pitch, boolean floor,
        float partialTick) {
        MaidRenderProtection.draw(() -> drawUnchecked(maid, x, y, scale, yaw, pitch, floor, partialTick));
    }

    private void drawUnchecked(EntityMaid maid, float x, float y, float scale, float yaw, float pitch, boolean floor,
        float partialTick) {
        var manager = RenderManager.instance;
        float viewX = manager.playerViewX, viewY = manager.playerViewY;
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        try (var filters = ClientCompatServices.renderPlatform()
            .captureFilterWindow(); var scope = new MaidItemRenderScope()) {
            GL11.glColor4f(
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glTranslatef(x, y, ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_Z);
            GL11.glScalef(
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.UNSET_OR_NOT_FOUND);
            GL11.glTranslatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_Z_2);
            GL11.glScalef(scale, scale, scale);
            GL11.glRotatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_ANGLE_DEG,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glRotatef(
                yaw,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glRotatef(
                -pitch,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            RenderHelper.enableStandardItemLighting();
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                ClientRenderLayout.DETAILS_PREVIEW_SET_LIGHTMAP_TEXTURE_COORDS_SECOND_P,
                ClientRenderLayout.DETAILS_PREVIEW_SET_LIGHTMAP_TEXTURE_COORDS_SECOND_P);
            manager.playerViewX = pitch;
            manager.playerViewY = ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X;
            // 原版详情关闭实体阴影；直接走生物本体绘制，保留其前后事件和装备层
            var renderer = manager.getEntityRenderObject(maid);
            long before = MaidHeldItemLayer.getNativeCounters().attempts;
            renderer.doRender(
                maid,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                partialTick);
            if (DevelopmentFlags.isDevCommandsEnabled() && renderer instanceof MaidRenderer maidRenderer) {
                diagnostic = maidRenderer.inspectPreviewForDevelopment(maid);
                diagnostic.addProperty(
                    ClientRenderIds.ID_NATIVEITEMCALLS,
                    MaidHeldItemLayer.getNativeCounters().attempts - before);
            }
            GL11.glTranslatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.HALF_FACTOR_F,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glColor4f(
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
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
                throw new IllegalStateException(ClientRenderDiagnostics.MSG_CANNOT_LOAD_BUILTIN_PREVIEW_STOOL, failure);
            }
        }
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_DRAW_STOOL_OFFSET_Y,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glRotatef(
                ClientRenderLayout.HALF_TURN_DEGREES,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glScalef(
                ClientRenderLayout.UNSET_OR_NOT_FOUND,
                ClientRenderLayout.UNSET_OR_NOT_FOUND,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glTranslatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_DRAW_STOOL_OFFSET_Y_2,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            mc.getTextureManager()
                .bindTexture(STOOL_TEXTURE);
            var consumer = TessellatorVertexConsumer.INSTANCE;
            consumer.begin();
            try {
                stool.renderToBuffer(
                    new LegacyPoseStack(),
                    consumer,
                    ClientRenderLayout.RGB_COLOR_F000F0,
                    ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                    ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                    ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                    ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                    ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
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
            GL11.glTranslatef(x, y, ClientRenderLayout.DETAILS_PREVIEW_AXES_OFFSET_Z);
            GL11.glRotatef(
                -RenderManager.instance.playerViewX,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glRotatef(
                RenderManager.instance.playerViewY,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            GL11.glScalef(
                ClientRenderLayout.UNSET_OR_NOT_FOUND,
                ClientRenderLayout.UNSET_OR_NOT_FOUND,
                ClientRenderLayout.UNSET_OR_NOT_FOUND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            drawAxes(ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM, false);
            drawAxes(ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2, true);
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
        for (int i = 0; i < ClientRenderLayout.MIN_I_THRESHOLD; i++) {
            float x = i == 0 ? ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL
                : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                y = i == ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL ? ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL
                    : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                z = i == ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2
                    ? ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL
                    : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X;
            tessellator.setColorRGBA_F(
                colored ? x : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                colored ? y : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                colored ? z : ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            tessellator.setNormal(x, y, z);
            tessellator.addVertex(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X);
            tessellator.addVertex(
                x * ClientRenderLayout.DETAILS_PREVIEW_ADD_VERTEX_FIRST_PARAM,
                y * ClientRenderLayout.DETAILS_PREVIEW_ADD_VERTEX_FIRST_PARAM,
                z * ClientRenderLayout.DETAILS_PREVIEW_ADD_VERTEX_FIRST_PARAM);
        }
        tessellator.draw();
    }

    public JsonObject observation() {
        return new JsonParser().parse(diagnostic.toString())
            .getAsJsonObject();
    }

    @Override
    public void close() {
        stool = null;
    }
}
