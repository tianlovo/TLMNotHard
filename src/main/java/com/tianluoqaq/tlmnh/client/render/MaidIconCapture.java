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
        if (imageWidth < 256 || frameHeight < 256)
            throw new IllegalStateException("Model icon capture requires 256 pixels");
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        CacheIconTexture texture = null;
        try (var filters = MaidRenderPlatformState.captureFilterWindow(); var scope = new MaidItemRenderScope()) {
            var resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int size = (int) Math.ceil(256.0 / resolution.getScaleFactor());
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, frameHeight - 256, 256, 256);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            Gui.drawRect(0, 0, size, size + 2, 0xFF00FF00);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glTranslatef(0, 0, 200);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            MaidGuiPreview.draw(maid, size / 2, size, (int) (size / 2 * itemScale), -25, -20, 0, 0, size, size + 2);
            int count = imageWidth * imageHeight;
            if (pixels == null || pixels.capacity() < count) pixels = BufferUtils.createIntBuffer(count);
            pixels.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (fbo) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            } else GL11
                .glReadPixels(0, 0, imageWidth, imageHeight, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
                int pixel = pixels.get((frameHeight - 1 - y) * imageWidth + x);
                image.setRGB(x, y, pixel == 0xFF00FF00 ? 0 : pixel);
            }
            texture = new CacheIconTexture(image);
            if (!mc.getTextureManager()
                .loadTexture(location, texture)) throw new IllegalStateException("Model icon upload failed");
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
