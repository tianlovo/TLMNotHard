package com.tianluoqaq.tlmnh.client.lifecycle;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Indev可见墓碑占位，原版墓碑模型与客户端外观另行验收 */
@SideOnly(Side.CLIENT)
public final class TombstoneRenderer extends Render {

    private final RenderBlocks blocks = new RenderBlocks();

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        GL11.glPushAttrib(GL11.GL_CURRENT_BIT | GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glPushMatrix();
        try {
            bindTexture(TextureMap.locationBlocksTexture);
            GL11.glTranslated(x, y + 0.6D, z);
            GL11.glScalef(0.65F, 1.2F, 0.35F);
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glColor4f(1, 1, 1, 1);
            blocks.renderBlockAsItem(Blocks.stonebrick, 0, entity.getBrightness(partialTick));
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return TextureMap.locationBlocksTexture;
    }
}
