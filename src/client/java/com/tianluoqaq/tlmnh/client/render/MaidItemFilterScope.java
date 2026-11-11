package com.tianluoqaq.tlmnh.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GLContext;

import com.tianluoqaq.tlmnh.compat.angelica.client.render.MaidRenderPlatformState;

/** 保存本次物品图集采样与caller临时窗口，不调用会清掉caller窗口的全局restore函数 */
final class MaidItemFilterScope implements AutoCloseable {

    private final MaidRenderPlatformState.FilterWindow window = MaidRenderPlatformState.captureFilterWindow();
    private final int texture, min, mag;
    private final float aniso;
    private final boolean anisotropic = GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic;
    private boolean closed;

    MaidItemFilterScope(ItemStack stack) {
        var manager = Minecraft.getMinecraft()
            .getTextureManager();
        manager.bindTexture(manager.getResourceLocation(stack.getItemSpriteNumber()));
        texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        min = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        mag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
        aniso = anisotropic
            ? GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT)
            : 1;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        Throwable failure = null;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mag);
            if (anisotropic) GL11
                .glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso);
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                try {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
                } finally {
                    OpenGlHelper.setActiveTexture(active);
                }
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            try {
                window.restore();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
    }
}
