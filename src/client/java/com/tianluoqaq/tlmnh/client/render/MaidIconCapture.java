package com.tianluoqaq.tlmnh.client.render;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.texture.CacheIconTexture;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.client.render.scope.MaidRenderProtection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原版 IconCache 的主帧截图和纯绿抠除，使用旧版原生截图的像素格式 */
@SideOnly(Side.CLIENT)
public final class MaidIconCapture {

    private static IntBuffer pixels;

    private MaidIconCapture() {}

    public static CacheIconTexture capture(EntityMaid maid, float itemScale, ResourceLocation location) {
        return MaidRenderProtection.call(() -> captureUnchecked(maid, itemScale, location), () -> null);
    }

    private static CacheIconTexture captureUnchecked(EntityMaid maid, float itemScale, ResourceLocation location) {
        var mc = Minecraft.getMinecraft();
        var framebuffer = mc.getFramebuffer();
        boolean fbo = OpenGlHelper.isFramebufferEnabled();
        int imageWidth = fbo ? framebuffer.framebufferTextureWidth : mc.displayWidth;
        int imageHeight = fbo ? framebuffer.framebufferTextureHeight : mc.displayHeight;
        int frameHeight = fbo ? framebuffer.framebufferHeight : mc.displayHeight;
        if (imageWidth < ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD
            || frameHeight < ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD)
            throw new IllegalStateException(ClientRenderDiagnostics.MSG_MODEL_ICON_CAPTURE_REQUIRES_256);
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        CacheIconTexture texture = null;
        try (var filters = ClientCompatServices.renderPlatform()
            .captureFilterWindow(); var scope = new MaidItemRenderScope()) {
            var resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int size = (int) Math.ceil(ClientRenderLayout.ICON_CAPTURE_CEIL_FIRST_FACTOR / resolution.getScaleFactor());
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                frameHeight - ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD,
                ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD,
                ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD);
            GL11.glDepthMask(true);
            GL11.glClearDepth(ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            Gui.drawRect(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                size,
                size + ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2,
                ClientRenderLayout.ARGB_COLOR_FF00FF00);
            GL11.glColor4f(
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL,
                ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glTranslatef(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.ICON_CAPTURE_CAPTURE_UNCHECKED_OFFSET_Z);
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                ClientRenderLayout.DETAILS_PREVIEW_SET_LIGHTMAP_TEXTURE_COORDS_SECOND_P,
                ClientRenderLayout.DETAILS_PREVIEW_SET_LIGHTMAP_TEXTURE_COORDS_SECOND_P);
            MaidGuiPreview.draw(
                maid,
                size / ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2,
                size,
                (int) (size / ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2 * itemScale),
                ClientRenderLayout.ICON_CAPTURE_DRAW_FIFTH_PARAM,
                ClientRenderLayout.DETAILS_PREVIEW_AXES_OFFSET_Z,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                size,
                size + ClientRenderLayout.DETAILS_PREVIEW_DRAW_AXES_FIRST_PARAM_2);
            int count = imageWidth * imageHeight;
            if (pixels == null || pixels.capacity() < count) pixels = BufferUtils.createIntBuffer(count);
            pixels.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, ClientRenderLayout.DETAILS_PREVIEW_RED_LEVEL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (fbo) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
                GL11.glGetTexImage(
                    GL11.GL_TEXTURE_2D,
                    ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                    GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                    pixels);
            } else GL11.glReadPixels(
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X,
                imageWidth,
                imageHeight,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                pixels);
            var image = new BufferedImage(
                ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD,
                ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD,
                BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD; y++)
                for (int x = 0; x < ClientRenderLayout.MIN_IMAGE_WIDTH_THRESHOLD; x++) {
                    int pixel = pixels.get((frameHeight - 1 - y) * imageWidth + x);
                    image.setRGB(
                        x,
                        y,
                        pixel == ClientRenderLayout.ARGB_COLOR_FF00FF00
                            ? ClientRenderLayout.DETAILS_PREVIEW_RENDER_OFFSET_X
                            : pixel);
                }
            texture = new CacheIconTexture(image);
            if (!mc.getTextureManager()
                .loadTexture(location, texture))
                throw new IllegalStateException(ClientRenderDiagnostics.MSG_MODEL_ICON_UPLOAD_FAILED);
            return texture;
        } catch (RuntimeException | Error failure) {
            if (texture != null) texture.deleteGlTexture();
            throw failure;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        }
    }

    public static void releaseScratch() {
        pixels = null;
    }
}
