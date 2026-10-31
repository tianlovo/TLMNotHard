/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/texture/CacheIconTexture.java, MIT
 * Adaptation: 1.7.10 BufferedImage upload and explicit GPU lifetime
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.texture;

import java.awt.image.BufferedImage;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CacheIconTexture extends AbstractTexture {

    private BufferedImage image;
    private final int visiblePixels;

    public CacheIconTexture(BufferedImage image) {
        this.image = image;
        int visible = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) visible++;
        visiblePixels = visible;
    }

    @Override
    public void loadTexture(IResourceManager manager) {
        if (image == null) return;
        try {
            TextureUtil.uploadTextureImageAllocate(getGlTextureId(), image, false, false);
        } finally {
            image = null;
        }
    }

    public int visiblePixels() {
        return visiblePixels;
    }
}
